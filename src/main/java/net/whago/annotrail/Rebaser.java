package net.whago.annotrail;

import static net.whago.annotrail.Models.*;
import com.google.gson.GsonBuilder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.color.*;
import org.apache.pdfbox.pdmodel.interactive.annotation.*;

/** Read-only analysis and explicitly reviewed transfer. This is not a hostile-PDF sandbox. */
public final class Rebaser {
    public static final String VERSION = "0.1.2";
    public static final long MAX_INPUT_BYTES = 256L * 1024 * 1024;
    public static final int MAX_PLAN_ENTRY_BYTES = 8 * 1024 * 1024;
    private Rebaser() { }

    public static Plan analyze(Path oldPdf, Path newPdf, Options options, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(cancelled, "cancelled"); validateOptions(options);
        TextIndex.check(cancelled);
        Stamp oldStamp = stamp(oldPdf,cancelled), newStamp = stamp(newPdf,cancelled);
        List<String> warnings = new ArrayList<>(); List<Entry> entries = new ArrayList<>();
        try (PDDocument oldDoc = Loader.loadPDF(oldPdf.toFile()); PDDocument newDoc = Loader.loadPDF(newPdf.toFile())) {
            secure(oldDoc, false, cancelled); secure(newDoc, true, cancelled);
            pageLimit(oldDoc,options); pageLimit(newDoc,options);
            List<TextIndex> oldText = index(oldDoc,options,warnings,"Original",cancelled);
            List<TextIndex> newText = index(newDoc,options,warnings,"Revised",cancelled);
            int count = 0; long planEntryBytes = 0;
            var budgetJson = new GsonBuilder().setPrettyPrinting().create();
            for (int p = 0; p < oldDoc.getNumberOfPages(); p++) {
                TextIndex.check(cancelled); List<PDAnnotation> annotations = oldDoc.getPage(p).getAnnotations();
                for (int a = 0; a < annotations.size(); a++) {
                    TextIndex.check(cancelled);
                    if (++count > options.maxAnnotations()) throw new IOException("Original PDF exceeds the annotation limit.");
                    PDAnnotation annotation = annotations.get(a); String type = safe(annotation.getSubtype());
                    String comment = safe(annotation.getContents());
                    String author = annotation instanceof PDAnnotationMarkup m ? safe(m.getTitlePopup()) : "";
                    if (comment.length() > 65_536 || author.length() > 4_096) throw new IOException("Annotation metadata exceeds the text limit.");
                    String quote = "", status = "unsupported", reason;
                    List<Candidate> candidates = new ArrayList<>(); TextIndex source = oldText.get(p);
                    boolean note = "Text".equals(type);
                    if (!Set.of("Highlight","Underline","StrikeOut","Text").contains(type))
                        reason = "This annotation type is not supported.";
                    else if (source.rotated) reason = "Rotated page or glyphs require manual transfer.";
                    else if (source.text.isEmpty()) reason = "No selectable text is available on the source page.";
                    else {
                        if (note) quote = source.nearby(annotation.getRectangle());
                        else if (annotation instanceof PDAnnotationTextMarkup mark && validQuads(mark.getQuadPoints()))
                            quote = source.quote(mark.getQuadPoints(),cancelled);
                        if (quote.isBlank()) reason = "The annotation has no usable selectable-text anchor.";
                        else {
                            int matches = 0;
                            for (int np = 0; np < newText.size(); np++) {
                                TextIndex.check(cancelled); TextIndex target = newText.get(np);
                                if (target.rotated) continue;
                                int from = 0, found;
                                while ((found = target.text.indexOf(quote,from)) >= 0) {
                                    TextIndex.check(cancelled); from = found + 1;
                                    if (!wordBoundaries(target.text,found,quote)) continue;
                                    List<Float> quads = target.quads(found,quote.length());
                                    if (quads.isEmpty()) continue;
                                    matches++;
                                    if (candidates.size() < options.maxCandidates()) {
                                        int left = Math.max(0,found-70), right = Math.min(target.text.length(),found+quote.length()+70);
                                        candidates.add(new Candidate(np+1,target.text.substring(left,right),1.0,quads));
                                    }
                                    // Only the ambiguity and displayed candidates matter after the cap.
                                    if (matches > options.maxCandidates()) break;
                                }
                                if (matches > options.maxCandidates()) break;
                            }
                            if (matches == 0) {
                                status = "removed";
                                reason = "Exact text was not found on supported revised pages; wording may have changed.";
                            } else if (matches == 1 && !note && strongAnchor(quote)) {
                                status = "confident";
                                reason = "One exact normalized-text match; this does not establish semantic equivalence.";
                            } else {
                                status = "ambiguous";
                                reason = note ? "A nearby line anchors this note; explicitly review its destination."
                                    : matches == 1 ? "The anchor is short; explicitly review its destination."
                                    : "The same text appears more than once; explicitly choose a destination.";
                                if (matches > options.maxCandidates()) reason += " Candidate list truncated at " + options.maxCandidates() + ".";
                            }
                        }
                    }
                    Entry entry = new Entry("p"+(p+1)+"-a"+a,p+1,type,quote,comment,author,color(annotation),status,reason,candidates);
                    String encodedEntry=budgetJson.toJson(entry);
                    // A standalone pretty-printed entry gains four spaces per line inside plan.entries.
                    planEntryBytes += encodedEntry.getBytes(StandardCharsets.UTF_8).length + 4 * (1 + encodedEntry.chars().filter(c->c=='\n').count());
                    if(planEntryBytes>MAX_PLAN_ENTRY_BYTES)throw new IOException("Review plan exceeds the 8 MiB entry budget. Reduce annotations or split the PDF.");
                    entries.add(entry);
                }
            }
            if (entries.isEmpty()) warnings.add("Original PDF contains no embedded annotations. Export annotations into the PDF before analysis.");
            verify(oldPdf,oldStamp,cancelled); verify(newPdf,newStamp,cancelled);
            return new Plan(1,VERSION,new Input(oldPdf.getFileName().toString(),oldStamp.hash,oldStamp.bytes,oldDoc.getNumberOfPages()),
                new Input(newPdf.getFileName().toString(),newStamp.hash,newStamp.bytes,newDoc.getNumberOfPages()),entries,warnings);
        } catch (TextIndex.StopExtraction stop) {
            if (stop.limit) throw new IOException("Selectable text exceeds the character limit.");
            throw new InterruptedIOException("Operation cancelled; no completed output was published.");
        } catch (RuntimeException invalid) {
            throw new IOException("PDF analysis failed: invalid or unsupported PDF structure.",invalid);
        }
    }

    public static Result export(Path oldPdf, Path newPdf, Plan plan, Map<String,Integer> choices,
                                Path outputPdf, Path reportJson, BooleanSupplier cancelled) throws IOException {
        return export(oldPdf,newPdf,plan,choices,outputPdf,reportJson,cancelled,new OutputPublication.FileOperations());
    }

    static Result export(Path oldPdf, Path newPdf, Plan plan, Map<String,Integer> choices,
                         Path outputPdf, Path reportJson, BooleanSupplier cancelled,
                         OutputPublication.FileOperations files) throws IOException {
        TextIndex.check(cancelled);
        if (plan == null || choices == null) throw new IOException("A review plan and explicit choices are required.");
        Path output = destination(outputPdf), report = destination(reportJson);
        Path oldReal = oldPdf.toRealPath(), newReal = newPdf.toRealPath();
        if (output.equals(report) || output.equals(oldReal) || output.equals(newReal) || report.equals(oldReal) || report.equals(newReal))
            throw new IOException("Output and report must be distinct new files, separate from both inputs.");
        // Saved JSON is untrusted. Never apply user-supplied coordinates without reproducing the plan.
        Plan fresh = analyze(oldPdf,newPdf,Options.defaults(),cancelled);
        if (!fresh.equals(plan)) throw new IOException("The plan is stale, edited, or was produced with incompatible options. Analyze these PDFs again.");
        Set<String> ids = new HashSet<>(); int transferred = 0;
        for (Entry e : fresh.entries()) {
            ids.add(e.id()); Integer choice = choices.get(e.id());
            if (choice == null || choice < -1 || choice >= e.candidates().size())
                throw new IOException("Every annotation needs an explicit valid candidate or skip choice: " + e.id());
            if (choice >= 0) transferred++;
        }
        if (!ids.equals(choices.keySet())) throw new IOException("Choices contain unknown annotation IDs.");
        Result result = new Result(transferred,fresh.entries().size()-transferred,fresh.warnings());
        Path pdfTemp = null, reportTemp = null;
        try (OutputPublication publication=new OutputPublication(files,cancelled)) {
            pdfTemp = Files.createTempFile(output.getParent(),".annotrail-",".pdf.tmp");
            reportTemp = Files.createTempFile(report.getParent(),".annotrail-",".json.tmp");
            try (PDDocument original = Loader.loadPDF(oldPdf.toFile()); PDDocument revised = Loader.loadPDF(newPdf.toFile())) {
                secure(original,false,cancelled); secure(revised,true,cancelled);
                for (Entry entry : fresh.entries()) {
                    TextIndex.check(cancelled); int choice = choices.get(entry.id()); if (choice < 0) continue;
                    Candidate candidate = entry.candidates().get(choice);
                    PDPage page = revised.getPage(candidate.page()-1);
                    PDAnnotationMarkup target = create(entry.type());
                    target.setContents(entry.comment()); target.setTitlePopup(entry.author());
                    if (!entry.color().isEmpty()) {
                        float[] components = new float[entry.color().size()];
                        for (int c=0;c<components.length;c++) components[c]=entry.color().get(c);
                        PDColorSpace space = switch (components.length) { case 1 -> PDDeviceGray.INSTANCE; case 4 -> PDDeviceCMYK.INSTANCE; default -> PDDeviceRGB.INSTANCE; };
                        target.setColor(new PDColor(components,space));
                    }
                    String marker = "annotrail-"+fresh.original().sha256()+"-"+entry.id();
                    for(PDPage existingPage:revised.getPages()) for(PDAnnotation existing:existingPage.getAnnotations())
                        if(marker.equals(existing.getAnnotationName())) throw new IOException("This annotation has already been transferred from the same original PDF: "+entry.id());
                    int annotationIndex = Integer.parseInt(entry.id().substring(entry.id().indexOf("-a")+2));
                    PDAnnotation source = original.getPage(entry.sourcePage()-1).getAnnotations().get(annotationIndex);
                    if (source instanceof PDAnnotationMarkup m && Float.isFinite(m.getConstantOpacity()))
                        target.setConstantOpacity(Math.max(0,Math.min(1,m.getConstantOpacity())));
                    float[] quads = array(candidate.quads()); PDRectangle rect = rectangle(quads);
                    if (target instanceof PDAnnotationTextMarkup mark) mark.setQuadPoints(quads);
                    else { // Standalone notes are intentionally review-only and positioned next to their anchor.
                        PDRectangle crop=page.getCropBox();float size=Math.min(20,Math.min(crop.getWidth(),crop.getHeight()));
                        float x=Math.max(crop.getLowerLeftX(),Math.min(rect.getLowerLeftX(),crop.getUpperRightX()-size));
                        float y=Math.max(crop.getLowerLeftY(),Math.min(rect.getUpperRightY(),crop.getUpperRightY()-size));
                        rect = new PDRectangle(x,y,size,size);
                        ((PDAnnotationText)target).setName(PDAnnotationText.NAME_COMMENT);
                    }
                    target.setRectangle(rect); target.setPage(page); target.setPrinted(true);
                    target.setAnnotationName(marker);
                    page.getAnnotations().add(target); target.constructAppearances(revised);
                }
                TextIndex.check(cancelled); revised.save(pdfTemp.toFile());
                try(PDDocument saved=Loader.loadPDF(pdfTemp.toFile())) {
                    if(saved.getNumberOfPages()!=revised.getNumberOfPages())throw new IOException("Saved output failed page verification.");
                    for(int pageNumber=0;pageNumber<revised.getNumberOfPages();pageNumber++) {
                        TextIndex.check(cancelled);
                        List<PDAnnotation> expected=revised.getPage(pageNumber).getAnnotations(),actual=saved.getPage(pageNumber).getAnnotations();
                        if(expected.size()!=actual.size())throw new IOException("Saved output failed annotation verification.");
                        for(int n=0;n<expected.size();n++) {
                            PDAnnotation e=expected.get(n),a=actual.get(n);
                            if(!Objects.equals(e.getSubtype(),a.getSubtype()) || !Objects.equals(e.getContents(),a.getContents())
                                || !Objects.equals(e.getAnnotationName(),a.getAnnotationName()))throw new IOException("Saved output failed annotation verification.");
                            if(e instanceof PDAnnotationTextMarkup em && a instanceof PDAnnotationTextMarkup am
                                && !Arrays.equals(em.getQuadPoints(),am.getQuadPoints()))throw new IOException("Saved output failed coordinate verification.");
                        }
                    }
                }
            }
            Map<String,Object> document = new LinkedHashMap<>(); document.put("plan",fresh);
            document.put("choices",new TreeMap<>(choices)); document.put("result",result);
            Files.writeString(reportTemp,new GsonBuilder().setPrettyPrinting().create().toJson(document)+"\n",StandardCharsets.UTF_8);
            TextIndex.check(cancelled);
            verify(oldPdf,new Stamp(fresh.original().sha256(),fresh.original().bytes()),cancelled);
            verify(newPdf,new Stamp(fresh.revised().sha256(),fresh.revised().bytes()),cancelled);
            publication.publish(pdfTemp,output);
            TextIndex.check(cancelled);
            publication.publish(reportTemp,report);
            TextIndex.check(cancelled);
            publication.commit();
            return result;
        } catch (IOException | RuntimeException failure) {
            for(Throwable suppressed:failure.getSuppressed())
                if(suppressed instanceof OutputPublication.RollbackException)
                    throw new IOException(failure.getMessage()+" "+suppressed.getMessage(),failure);
            if (failure instanceof IOException io) throw io;
            throw new IOException("PDF export failed; no completed output was published.",failure);
        } finally {
            if (pdfTemp != null) Files.deleteIfExists(pdfTemp);
            if (reportTemp != null) Files.deleteIfExists(reportTemp);
        }
    }

    private static void validateOptions(Options o) throws IOException {
        Options d = Options.defaults();
        if (o == null || o.maxPages()<1 || o.maxPages()>d.maxPages() || o.maxCharacters()<1 || o.maxCharacters()>d.maxCharacters()
            || o.maxAnnotations()<1 || o.maxAnnotations()>d.maxAnnotations() || o.maxCandidates()<1 || o.maxCandidates()>d.maxCandidates())
            throw new IOException("Limits must be positive and no larger than the built-in safety limits.");
    }
    private static void pageLimit(PDDocument doc, Options options) throws IOException {
        if (doc.getNumberOfPages()>options.maxPages()) throw new IOException("PDF exceeds the page limit.");
    }
    private static List<TextIndex> index(PDDocument doc,Options options,List<String>warnings,String label,BooleanSupplier cancelled) throws IOException {
        List<TextIndex> result = new ArrayList<>(); int budget=options.maxCharacters();
        for (int p=0;p<doc.getNumberOfPages();p++) {
            TextIndex.check(cancelled); TextIndex text = TextIndex.read(doc,p,budget,cancelled);
            budget-=text.text.length(); result.add(text);
            if(text.rotated) warnings.add(label+" page "+(p+1)+": rotated page or text is unsupported.");
            if(text.text.isEmpty()) warnings.add(label+" page "+(p+1)+": no selectable text; OCR is not performed.");
        }
        return result;
    }
    private static boolean strongAnchor(String quote) {
        long cjk = quote.codePoints().filter(c -> Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN
            || Character.UnicodeScript.of(c)==Character.UnicodeScript.HANGUL || Character.UnicodeScript.of(c)==Character.UnicodeScript.HIRAGANA
            || Character.UnicodeScript.of(c)==Character.UnicodeScript.KATAKANA).count();
        return cjk>=12 || (quote.length()>=20 && quote.trim().split("\\s+").length>=3);
    }
    private static boolean wordBoundaries(String text,int start,String quote) {
        int end=start+quote.length();
        return !(start>0 && westernWord(quote.codePointAt(0)) && westernWord(text.codePointBefore(start)))
            && !(end<text.length() && westernWord(quote.codePointBefore(quote.length())) && westernWord(text.codePointAt(end)));
    }
    private static boolean westernWord(int c) {
        Character.UnicodeScript script=Character.UnicodeScript.of(c);
        return Character.isDigit(c) || (Character.isLetter(c) && script!=Character.UnicodeScript.HAN
            && script!=Character.UnicodeScript.HANGUL && script!=Character.UnicodeScript.HIRAGANA && script!=Character.UnicodeScript.KATAKANA);
    }
    private static PDAnnotationMarkup create(String type) throws IOException {
        return switch(type) {
            case "Highlight" -> new PDAnnotationHighlight(); case "Underline" -> new PDAnnotationUnderline();
            case "StrikeOut" -> new PDAnnotationStrikeout(); case "Text" -> new PDAnnotationText();
            default -> throw new IOException("Unsupported annotation type.");
        };
    }
    private static List<Float> color(PDAnnotation a) {
        PDColor c = a.getColor(); if(c==null) return List.of(); float[] values=c.getComponents();
        if(values.length!=1 && values.length!=3 && values.length!=4) return List.of();
        List<Float> result=new ArrayList<>(); for(float value:values) { if(!Float.isFinite(value)) return List.of(); result.add(value); } return result;
    }
    private static String safe(String value) { return value==null ? "" : value; }
    private static boolean validQuads(float[] q) {
        if(q==null || q.length==0 || q.length%8!=0 || q.length>4_096) return false;
        for(float v:q) if(!Float.isFinite(v)) return false;
        for(int i=0;i<q.length;i+=8){float minX=Float.POSITIVE_INFINITY,minY=minX,maxX=Float.NEGATIVE_INFINITY,maxY=maxX;
            for(int n=0;n<4;n++){minX=Math.min(minX,q[i+2*n]);maxX=Math.max(maxX,q[i+2*n]);minY=Math.min(minY,q[i+2*n+1]);maxY=Math.max(maxY,q[i+2*n+1]);}
            if(maxX-minX<0.01 || maxY-minY<0.01)return false;
        }
        return true;
    }
    private static float[] array(List<Float> values) { float[] a=new float[values.size()]; for(int i=0;i<a.length;i++)a[i]=values.get(i);return a; }
    private static PDRectangle rectangle(float[] q) {
        float left=Float.POSITIVE_INFINITY,bottom=left,right=Float.NEGATIVE_INFINITY,top=right;
        for(int i=0;i<q.length;i+=2) { left=Math.min(left,q[i]);right=Math.max(right,q[i]);bottom=Math.min(bottom,q[i+1]);top=Math.max(top,q[i+1]); }
        return new PDRectangle(left,bottom,right-left,top-bottom);
    }
    private record Stamp(String hash,long bytes) { }
    private static Stamp stamp(Path path,BooleanSupplier cancelled) throws IOException {
        if(!Files.isRegularFile(path)) throw new IOException("Input is not a regular PDF file.");
        long bytes=Files.size(path); if(bytes>MAX_INPUT_BYTES) throw new IOException("Input exceeds the 256 MiB file limit.");
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256"); long read=0;
            try(InputStream in=Files.newInputStream(path)) { byte[] chunk=new byte[65_536]; int n; while((n=in.read(chunk))>=0) {
                TextIndex.check(cancelled); read+=n; if(read>MAX_INPUT_BYTES)throw new IOException("Input exceeds the 256 MiB file limit."); digest.update(chunk,0,n);
            }}
            if(read!=bytes)throw new IOException("Input changed while it was being read.");
            return new Stamp(HexFormat.of().formatHex(digest.digest()),bytes);
        }catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    private static void verify(Path path,Stamp expected,BooleanSupplier cancelled)throws IOException {
        if(!expected.equals(stamp(path,cancelled))) throw new IOException("Input changed while processing; analyze it again.");
    }
    private static Path destination(Path path)throws IOException {
        Path absolute=path.toAbsolutePath().normalize();
        if(absolute.getFileName()==null || absolute.getParent()==null)throw new IOException("Invalid output path.");
        Path target=absolute.getParent().toRealPath().resolve(absolute.getFileName());
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))throw new IOException("Output already exists; choose a new filename.");
        return target;
    }
    private static void secure(PDDocument doc,boolean revised,BooleanSupplier cancelled)throws IOException {
        if(doc.isEncrypted())throw new IOException("Encrypted PDFs are not supported. Provide an unencrypted copy.");
        if(revised && !doc.getSignatureDictionaries().isEmpty())throw new IOException("The revised PDF is signed; transfer would invalidate its signature.");
        Set<COSBase> visited=Collections.newSetFromMap(new IdentityHashMap<>()); ArrayDeque<COSBase> queue=new ArrayDeque<>();
        queue.add(doc.getDocument().getTrailer());
        while(!queue.isEmpty()) {
            TextIndex.check(cancelled); COSBase base=queue.removeLast(); if(!visited.add(base))continue;
            if(visited.size()>500_000)throw new IOException("PDF object structure exceeds the safety limit.");
            if(base instanceof COSObject object) { if(object.getObject()!=null)queue.add(object.getObject()); }
            else if(base instanceof COSArray array) { for(COSBase value:array)if(value!=null)queue.add(value); }
            else if(base instanceof COSDictionary dict) {
                for(String key:List.of("JS","AA","EmbeddedFiles","EF","XFA","OpenAction"))
                    if(dict.containsKey(COSName.getPDFName(key)))throw new IOException("PDF contains prohibited active content or embedded files ("+key+").");
                String action=dict.getNameAsString(COSName.S);
                if("URI".equals(action)) {
                    String uri=dict.getString(COSName.URI,"");
                    try {
                        String scheme=new java.net.URI(uri).getScheme();
                        if(scheme==null || !Set.of("http","https","mailto").contains(scheme.toLowerCase(Locale.ROOT)))
                            throw new IOException("PDF contains a link with an unsupported URI scheme.");
                    } catch(java.net.URISyntaxException invalid) {throw new IOException("PDF contains an invalid URI link.",invalid);}
                }
                if(Set.of("JavaScript","Launch","GoToR","GoToE","SubmitForm","ImportData","Rendition","Movie","Sound","SetOCGState","Named","Hide").contains(safe(action)))
                    throw new IOException("PDF contains a prohibited action: "+action+".");
                for(COSBase value:dict.getValues())if(value!=null)queue.add(value);
            }
        }
    }
}
