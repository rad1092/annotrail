package net.whago.annotrail;

import static net.whago.annotrail.FixtureGenerator.*;
import static org.junit.jupiter.api.Assertions.*;
import net.whago.annotrail.Models.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.*;
import org.apache.pdfbox.pdmodel.interactive.annotation.*;
import org.apache.pdfbox.pdmodel.interactive.action.*;
import org.apache.pdfbox.pdmodel.interactive.form.*;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.util.Matrix;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class RebaserTest {
    @TempDir Path dir;
    private Path old(){return dir.resolve("old.pdf");} private Path newer(){return dir.resolve("new.pdf");}
    private Plan analyze()throws IOException{return Rebaser.analyze(old(),newer(),Options.defaults(),()->false);}
    private Map<String,Integer> accept(Plan p){Map<String,Integer> m=new LinkedHashMap<>();for(Entry e:p.entries())m.put(e.id(),e.status().equals("confident")?0:-1);return m;}
    private void pair(String source,String revised)throws IOException {
        try(PDDocument d=new PDDocument()){PDPage p=page(d);line(d,p,source,50,700);mark(p,"Highlight",source,50,700,"한글 주석 <script>alert(1)</script>");d.save(old().toFile());}
        try(PDDocument d=new PDDocument()){PDPage p=page(d);line(d,p,revised,80,580);d.save(newer().toFile());}
    }
    @Test void classificationAndReopenedExportPreserveInputsMetadataAndExistingAnnotations()throws Exception {
        generate(dir);byte[] a=Files.readAllBytes(old()),b=Files.readAllBytes(newer());Plan p=analyze();
        assertEquals(List.of("confident","ambiguous","removed","unsupported"),p.entries().stream().map(Entry::status).toList());
        assertEquals(UNIQUE,p.entries().get(0).quote());assertEquals(2,p.entries().get(0).candidates().get(0).page());
        assertEquals(2,p.entries().get(1).candidates().size());
        Map<String,Integer> choices=accept(p); choices.put(p.entries().get(1).id(),1);
        Result result=Rebaser.export(old(),newer(),p,choices,dir.resolve("out.pdf"),dir.resolve("report.json"),()->false);
        assertEquals(2,result.transferred());assertEquals(2,result.skipped());
        try(PDDocument output=Loader.loadPDF(dir.resolve("out.pdf").toFile())){
            assertEquals(1,output.getPage(0).getAnnotations().size());assertEquals(2,output.getPage(1).getAnnotations().size());
            PDAnnotationTextMarkup transferred=(PDAnnotationTextMarkup)output.getPage(1).getAnnotations().get(0);
            assertInstanceOf(PDAnnotationHighlight.class,transferred);assertEquals("Preserve numerical evidence.",transferred.getContents());
            assertEquals("Synthetic reviewer",transferred.getTitlePopup());assertEquals(0.6f,transferred.getConstantOpacity(),0.001);
            assertEquals(70,transferred.getQuadPoints()[0],0.1);assertTrue(transferred.getQuadPoints()[1]>660);assertTrue(transferred.getQuadPoints()[1]<674);
            assertNotNull(transferred.getAppearance());assertFalse(transferred.getCOSObject().containsKey(COSName.A));
        }
        assertArrayEquals(a,Files.readAllBytes(old()));assertArrayEquals(b,Files.readAllBytes(newer()));
        assertTrue(Files.readString(dir.resolve("report.json")).contains("unresolved note"));
    }
    @Test void preservesAllSupportedMarkupSubtypes()throws Exception {
        try(PDDocument d=new PDDocument()){PDPage p=page(d);int y=730;for(String type:List.of("Highlight","Underline","StrikeOut")){line(d,p,UNIQUE,y/10,y);mark(p,type,UNIQUE,y/10,y,"comment");y-=60;}d.save(old().toFile());}
        try(PDDocument d=new PDDocument()){PDPage p=page(d);line(d,p,UNIQUE,50,700);d.save(newer().toFile());}
        Plan plan=analyze();Rebaser.export(old(),newer(),plan,accept(plan),dir.resolve("out.pdf"),dir.resolve("report.json"),()->false);
        try(PDDocument d=Loader.loadPDF(dir.resolve("out.pdf").toFile())){assertEquals(List.of("Highlight","Underline","StrikeOut"),d.getPage(0).getAnnotations().stream().map(PDAnnotation::getSubtype).toList());}
    }
    @Test void matchesWrappedTextAcrossNewLineLayout()throws Exception {
        String first="The measured sample contains",second="forty two stable particles.";
        try(PDDocument d=new PDDocument()){PDPage p=page(d);line(d,p,first,50,700);line(d,p,second,50,680);
            PDAnnotationTextMarkup a=mark(p,"Highlight",first,50,700,"wrap");float[] q=a.getQuadPoints(),r={50,692,50+width(second),692,50,678,50+width(second),678};
            float[] both=Arrays.copyOf(q,16);System.arraycopy(r,0,both,8,8);a.setQuadPoints(both);d.save(old().toFile());}
        try(PDDocument d=new PDDocument()){PDPage p=page(d);line(d,p,first+" "+second,50,600);d.save(newer().toFile());}
        Plan p=analyze();assertEquals(first+" "+second,p.entries().get(0).quote());assertEquals("confident",p.entries().get(0).status());
    }
    @Test void sourceAndDestinationCropOffsetsAreRespected()throws Exception {
        try(PDDocument d=new PDDocument()){PDPage p=page(d);p.setCropBox(new PDRectangle(30,50,550,700));line(d,p,UNIQUE,80,680);mark(p,"Highlight",UNIQUE,80,680,"crop");d.save(old().toFile());}
        try(PDDocument d=new PDDocument()){PDPage p=page(d);p.setCropBox(new PDRectangle(40,70,540,680));line(d,p,UNIQUE,100,620);d.save(newer().toFile());}
        Entry e=analyze().entries().get(0);assertEquals(UNIQUE,e.quote());assertEquals("confident",e.status());
        assertEquals(100,e.candidates().get(0).quads().get(0),0.1);assertEquals(620,e.candidates().get(0).quads().get(5),0.1);
    }
    @Test void nearbyNotesAlwaysRequireReviewAndPreserveComment()throws Exception {
        pair(UNIQUE,UNIQUE);
        try(PDDocument d=Loader.loadPDF(old().toFile())){PDPage p=d.getPage(0);p.getAnnotations().clear();PDAnnotationText note=new PDAnnotationText();note.setRectangle(new PDRectangle(45,708,20,20));note.setContents("review-only note");p.getAnnotations().add(note);d.save(dir.resolve("note.pdf").toFile());}
        Files.move(dir.resolve("note.pdf"),old(),StandardCopyOption.REPLACE_EXISTING);
        Plan p=analyze();Entry e=p.entries().get(0);assertEquals("ambiguous",e.status());assertEquals(UNIQUE,e.quote());assertEquals(1,e.candidates().size());
        Rebaser.export(old(),newer(),p,Map.of(e.id(),0),dir.resolve("out.pdf"),dir.resolve("report.json"),()->false);
        try(PDDocument d=Loader.loadPDF(dir.resolve("out.pdf").toFile())){assertInstanceOf(PDAnnotationText.class,d.getPage(0).getAnnotations().get(0));assertEquals("review-only note",d.getPage(0).getAnnotations().get(0).getContents());}
    }
    @Test void shortUniqueQuoteNeedsReview()throws Exception {pair("Overview","Overview");assertEquals("ambiguous",analyze().entries().get(0).status());}
    @Test void rotatedSourcePageAndRotatedGlyphsAreUnsupported()throws Exception {
        pair(UNIQUE,UNIQUE);try(PDDocument d=Loader.loadPDF(old().toFile())){d.getPage(0).setRotation(90);d.save(dir.resolve("rot.pdf").toFile());}
        Files.move(dir.resolve("rot.pdf"),old(),StandardCopyOption.REPLACE_EXISTING);assertEquals("unsupported",analyze().entries().get(0).status());
        try(PDDocument d=new PDDocument()){PDPage p=page(d);try(PDPageContentStream c=new PDPageContentStream(d,p)){c.beginText();c.setFont(FONT,12);c.setTextMatrix(Matrix.getRotateInstance(Math.PI/2,100,500));c.showText(UNIQUE);c.endText();}mark(p,"Highlight",UNIQUE,50,700,"rotated");d.save(old().toFile());}
        assertEquals("unsupported",analyze().entries().get(0).status());
    }
    @Test void noSelectableTextAndInvalidQuadsAreExplicit()throws Exception {
        pair(UNIQUE,UNIQUE);try(PDDocument d=new PDDocument()){PDPage p=page(d);mark(p,"Highlight",UNIQUE,50,700,"scanned");d.save(old().toFile());}
        Plan p=analyze();assertEquals("unsupported",p.entries().get(0).status());assertTrue(p.warnings().stream().anyMatch(w->w.contains("no selectable text")));
        try(PDDocument d=new PDDocument()){PDPage page=page(d);line(d,page,UNIQUE,50,700);PDAnnotationTextMarkup a=mark(page,"Highlight",UNIQUE,50,700,"malformed");a.setQuadPoints(new float[]{1,2,3});d.save(old().toFile());}
        assertEquals("unsupported",analyze().entries().get(0).status());
    }
    @Test void capsPagesCharactersAnnotationsAndCandidateList()throws Exception {
        generate(dir);
        for(Options o:List.of(new Options(1,2_000_000,2000,20),new Options(500,20,2000,20),new Options(500,2_000_000,2,20)))
            assertThrows(IOException.class,()->Rebaser.analyze(old(),newer(),o,()->false));
        Plan p=Rebaser.analyze(old(),newer(),new Options(500,2_000_000,2000,1),()->false);
        assertEquals("ambiguous",p.entries().get(1).status());assertEquals(1,p.entries().get(1).candidates().size());assertTrue(p.entries().get(1).reason().contains("truncated"));
        assertThrows(IOException.class,()->Rebaser.analyze(old(),newer(),new Options(-1,20,20,20),()->false));
    }
    @Test void modifiedPlanAndModifiedInputAreRejected()throws Exception {
        pair(UNIQUE,UNIQUE);Plan p=analyze();Entry e=p.entries().get(0);Candidate c=e.candidates().get(0);
        Entry altered=new Entry(e.id(),e.sourcePage(),e.type(),e.quote(),"injected comment",e.author(),e.color(),e.status(),e.reason(),List.of(new Candidate(c.page(),c.context(),1,List.of(0f,0f,1f,0f,0f,1f,1f,1f))));
        Plan forged=new Plan(p.formatVersion(),p.version(),p.original(),p.revised(),List.of(altered),p.warnings());
        assertThrows(IOException.class,()->Rebaser.export(old(),newer(),forged,accept(p),dir.resolve("out.pdf"),dir.resolve("report.json"),()->false));
        Files.writeString(newer(),"\n% edited",StandardOpenOption.APPEND);
        assertThrows(IOException.class,()->Rebaser.export(old(),newer(),p,accept(p),dir.resolve("out.pdf"),dir.resolve("report.json"),()->false));
        assertFalse(Files.exists(dir.resolve("out.pdf")));
    }
    @Test void everyDecisionMustBeExplicitAndValid()throws Exception {
        generate(dir);Plan p=analyze();
        for(Map<String,Integer> choice:List.of(Map.<String,Integer>of(),Map.of("p1-a0",-2),Map.of("p1-a0",30),Map.of("unrelated",0)))
            assertThrows(IOException.class,()->Rebaser.export(old(),newer(),p,choice,dir.resolve("out.pdf"),dir.resolve("report.json"),()->false));
        Map<String,Integer> extra=accept(p);extra.put("unknown",-1);
        assertThrows(IOException.class,()->Rebaser.export(old(),newer(),p,extra,dir.resolve("out.pdf"),dir.resolve("report.json"),()->false));
    }
    @Test void outputConflictsAliasesAndFailedPublicationPreserveInputs()throws Exception {
        pair(UNIQUE,UNIQUE);Plan p=analyze();byte[] original=Files.readAllBytes(old());
        assertThrows(IOException.class,()->Rebaser.export(old(),newer(),p,accept(p),old(),dir.resolve("report.json"),()->false));
        assertThrows(IOException.class,()->Rebaser.export(old(),newer(),p,accept(p),dir.resolve("same"),dir.resolve("same"),()->false));
        Files.writeString(dir.resolve("report.json"),"existing");
        assertThrows(IOException.class,()->Rebaser.export(old(),newer(),p,accept(p),dir.resolve("out.pdf"),dir.resolve("report.json"),()->false));
        assertEquals("existing",Files.readString(dir.resolve("report.json")));assertFalse(Files.exists(dir.resolve("out.pdf")));assertArrayEquals(original,Files.readAllBytes(old()));
    }
    @Test void cancellationNeverPublishesOutputsOrChangesInputs()throws Exception {
        generate(dir);Plan p=analyze();byte[] a=Files.readAllBytes(old());
        assertThrows(InterruptedIOException.class,()->Rebaser.analyze(old(),newer(),Options.defaults(),()->true));
        AtomicInteger calls=new AtomicInteger();assertThrows(InterruptedIOException.class,()->Rebaser.analyze(old(),newer(),Options.defaults(),()->calls.incrementAndGet()>100));
        assertThrows(InterruptedIOException.class,()->Rebaser.export(old(),newer(),p,accept(p),dir.resolve("out.pdf"),dir.resolve("report.json"),()->true));
        assertFalse(Files.exists(dir.resolve("out.pdf")));assertArrayEquals(a,Files.readAllBytes(old()));
    }
    @Test void malformedPdfFailsClearly()throws Exception {pair(UNIQUE,UNIQUE);Files.writeString(newer(),"not a PDF");assertThrows(IOException.class,this::analyze);}
    @Test void javascriptAdditionalActionsAndEmbeddedFilesAreRejected()throws Exception {
        for(String key:List.of("JS","AA","EmbeddedFiles","EF","XFA")) {
            pair(UNIQUE,UNIQUE);try(PDDocument d=Loader.loadPDF(newer().toFile())){d.getDocumentCatalog().getCOSObject().setItem(COSName.getPDFName(key),new COSDictionary());d.save(dir.resolve("active.pdf").toFile());}
            Files.move(dir.resolve("active.pdf"),newer(),StandardCopyOption.REPLACE_EXISTING);assertThrows(IOException.class,this::analyze,key);
        }
    }
    @Test void activeLaunchBlockedButStaticLinksAllowed()throws Exception {
        pair(UNIQUE,UNIQUE);try(PDDocument d=Loader.loadPDF(newer().toFile())){PDAnnotationLink link=new PDAnnotationLink();link.setRectangle(new PDRectangle(10,10,20,20));PDActionURI action=new PDActionURI();action.setURI("https://example.invalid");link.setAction(action);d.getPage(0).getAnnotations().add(link);d.save(dir.resolve("link.pdf").toFile());}
        Files.move(dir.resolve("link.pdf"),newer(),StandardCopyOption.REPLACE_EXISTING);assertEquals("confident",analyze().entries().get(0).status());
        try(PDDocument d=Loader.loadPDF(newer().toFile())){PDAnnotationLink link=(PDAnnotationLink)d.getPage(0).getAnnotations().get(0);link.setAction(new PDActionLaunch());d.save(dir.resolve("active.pdf").toFile());}
        Files.move(dir.resolve("active.pdf"),newer(),StandardCopyOption.REPLACE_EXISTING);assertThrows(IOException.class,this::analyze);
    }
    @Test void encryptedAndSignedRevisedFilesAreRejected()throws Exception {
        pair(UNIQUE,UNIQUE);try(PDDocument d=Loader.loadPDF(newer().toFile())){d.protect(new StandardProtectionPolicy("owner","",new AccessPermission()));d.save(dir.resolve("enc.pdf").toFile());}
        Files.move(dir.resolve("enc.pdf"),newer(),StandardCopyOption.REPLACE_EXISTING);assertThrows(IOException.class,this::analyze);
        pair(UNIQUE,UNIQUE);try(PDDocument d=Loader.loadPDF(newer().toFile())){PDAcroForm form=new PDAcroForm(d);d.getDocumentCatalog().setAcroForm(form);PDSignatureField field=new PDSignatureField(form);field.setValue(new PDSignature());form.getFields().add(field);d.save(dir.resolve("signed.pdf").toFile());}
        Files.move(dir.resolve("signed.pdf"),newer(),StandardCopyOption.REPLACE_EXISTING);assertThrows(IOException.class,this::analyze);
    }
    @Test void koreanFilenamesAndUnicodeCommentsRoundtripWithoutLocalPaths()throws Exception {
        pair(UNIQUE,UNIQUE);Path a=dir.resolve("원본.pdf"),b=dir.resolve("수정본.pdf");Files.move(old(),a);Files.move(newer(),b);
        Plan p=Rebaser.analyze(a,b,Options.defaults(),()->false);assertEquals("원본.pdf",p.original().name());
        Path report=dir.resolve("검토.json"),output=dir.resolve("결과.pdf");Rebaser.export(a,b,p,accept(p),output,report,()->false);
        try(PDDocument d=Loader.loadPDF(output.toFile())){assertTrue(d.getPage(0).getAnnotations().get(0).getContents().startsWith("한글 주석"));}
        String json=Files.readString(report);assertFalse(json.contains(dir.toString()));assertFalse(json.contains("<script>"));assertTrue(json.contains("한글 주석"));
    }
    @Test void bothIsoCounterclockwiseAndAdobeQuadOrderingSelectWholeQuote()throws Exception {
        pair(UNIQUE,UNIQUE);
        try(PDDocument d=Loader.loadPDF(old().toFile())) {
            PDAnnotationTextMarkup a=(PDAnnotationTextMarkup)d.getPage(0).getAnnotations().get(0);
            float[] q=a.getQuadPoints();a.setQuadPoints(new float[]{q[4],q[5],q[6],q[7],q[2],q[3],q[0],q[1]});
            d.save(dir.resolve("ccw.pdf").toFile());
        }
        Files.move(dir.resolve("ccw.pdf"),old(),StandardCopyOption.REPLACE_EXISTING);
        Entry e=analyze().entries().get(0);assertEquals(UNIQUE,e.quote());assertEquals("confident",e.status());
    }
    @Test void textInsideDifferentWordsDoesNotBecomeConfident()throws Exception {
        pair("scientific data annotation","unscientific data annotations");
        Entry e=analyze().entries().get(0);assertEquals("removed",e.status());assertTrue(e.candidates().isEmpty());
    }
    @Test void invisibleOutsideCropTextIsNotADestination()throws Exception {
        pair(UNIQUE,UNIQUE);
        try(PDDocument d=Loader.loadPDF(newer().toFile())){d.getPage(0).setCropBox(new PDRectangle(300,300,200,200));d.save(dir.resolve("cropped.pdf").toFile());}
        Files.move(dir.resolve("cropped.pdf"),newer(),StandardCopyOption.REPLACE_EXISTING);
        assertEquals("removed",analyze().entries().get(0).status());
    }
    @Test void automaticallyOpenedUriActionIsRejected()throws Exception {
        pair(UNIQUE,UNIQUE);
        try(PDDocument d=Loader.loadPDF(newer().toFile())){PDActionURI action=new PDActionURI();action.setURI("https://example.invalid/automatic");d.getDocumentCatalog().setOpenAction(action);d.save(dir.resolve("active.pdf").toFile());}
        Files.move(dir.resolve("active.pdf"),newer(),StandardCopyOption.REPLACE_EXISTING);assertThrows(IOException.class,this::analyze);
    }
    @Test void retransferringSameSourceAnnotationIsRejectedWithoutOutput()throws Exception {
        pair(UNIQUE,UNIQUE);Plan first=analyze();Path output=dir.resolve("out.pdf");
        Rebaser.export(old(),newer(),first,accept(first),output,dir.resolve("report.json"),()->false);
        Plan second=Rebaser.analyze(old(),output,Options.defaults(),()->false);
        assertThrows(IOException.class,()->Rebaser.export(old(),output,second,accept(second),dir.resolve("twice.pdf"),dir.resolve("twice.json"),()->false));
        assertFalse(Files.exists(dir.resolve("twice.pdf")));assertFalse(Files.exists(dir.resolve("twice.json")));
        try(var listing=Files.list(dir)){assertFalse(listing.anyMatch(x->x.getFileName().toString().startsWith(".annotrail-")));}
    }
    @Test void cancellationAfterStagingCleansTemporaryFiles()throws Exception {
        pair(UNIQUE,UNIQUE);Plan p=analyze();AtomicInteger checks=new AtomicInteger();
        var cancel=(java.util.function.BooleanSupplier)()->{
            if(checks.incrementAndGet()%20!=0)return false;
            try(var files=Files.list(dir)){return files.anyMatch(f->f.getFileName().toString().startsWith(".annotrail-"));}catch(IOException e){return false;}
        };
        assertThrows(InterruptedIOException.class,()->Rebaser.export(old(),newer(),p,accept(p),dir.resolve("out.pdf"),dir.resolve("report.json"),cancel));
        assertFalse(Files.exists(dir.resolve("out.pdf")));assertFalse(Files.exists(dir.resolve("report.json")));
        try(var files=Files.list(dir)){assertFalse(files.anyMatch(f->f.getFileName().toString().startsWith(".annotrail-")));}
    }

    @Test void largeRepeatedMetadataFailsPlanBudgetBeforeCreatingUnusableJson()throws Exception {
        pair(UNIQUE,UNIQUE);
        try(PDDocument d=new PDDocument()){PDPage p=page(d);line(d,p,UNIQUE,50,700);String comment="x".repeat(60_000);
            for(int i=0;i<150;i++)mark(p,"Highlight",UNIQUE,50,700,comment);d.save(dir.resolve("many.pdf").toFile());}
        Files.move(dir.resolve("many.pdf"),old(),StandardCopyOption.REPLACE_EXISTING);
        IOException failure=assertThrows(IOException.class,this::analyze);assertTrue(failure.getMessage().contains("8 MiB"));
    }
    @Test void javascriptAndFileUriLinksAreNotSafeStaticLinks()throws Exception {
        for(String uri:List.of("javascript:alert(1)","file:///private/example.txt")) {
            pair(UNIQUE,UNIQUE);
            try(PDDocument d=Loader.loadPDF(newer().toFile())){PDAnnotationLink link=new PDAnnotationLink();link.setRectangle(new PDRectangle(1,1,20,20));
                PDActionURI action=new PDActionURI();action.setURI(uri);link.setAction(action);d.getPage(0).getAnnotations().add(link);d.save(dir.resolve("unsafe.pdf").toFile());}
            Files.move(dir.resolve("unsafe.pdf"),newer(),StandardCopyOption.REPLACE_EXISTING);assertThrows(IOException.class,this::analyze);
        }
    }

    @Test void lateCancellationRollsBackTheAlreadyPublishedPdf()throws Exception {
        pair(UNIQUE,UNIQUE);Plan p=analyze();Path output=dir.resolve("out.pdf"),report=dir.resolve("report.json");
        assertThrows(InterruptedIOException.class,()->Rebaser.export(old(),newer(),p,accept(p),output,report,()->Files.exists(output)));
        assertFalse(Files.exists(output));assertFalse(Files.exists(report));
        try(var files=Files.list(dir)){assertFalse(files.anyMatch(f->f.getFileName().toString().startsWith(".annotrail-")));}
    }
    @Test void lateReportCollisionDoesNotOverwriteAndRollsBackPdf()throws Exception {
        pair(UNIQUE,UNIQUE);Plan p=analyze();Path output=dir.resolve("out.pdf"),report=dir.resolve("report.json");
        var collision=(java.util.function.BooleanSupplier)()->{
            if(Files.exists(output) && !Files.exists(report))try{Files.writeString(report,"concurrently created",StandardOpenOption.CREATE_NEW);}catch(IOException ignored){}
            return false;
        };
        assertThrows(IOException.class,()->Rebaser.export(old(),newer(),p,accept(p),output,report,collision));
        assertFalse(Files.exists(output));assertEquals("concurrently created",Files.readString(report));
        try(var files=Files.list(dir)){assertFalse(files.anyMatch(f->f.getFileName().toString().startsWith(".annotrail-")));}
    }

    @Test void disconnectedHighlightsDoNotInventAConfidentJoinedSentence()throws Exception {
        String first="This important conclusion",gap=" is not ",last="supported by current evidence.";
        pair(first+gap+last,first+" "+last);
        try(PDDocument d=Loader.loadPDF(old().toFile())) {
            PDPage p=d.getPage(0);p.getAnnotations().clear();
            PDAnnotationTextMarkup a=mark(p,"Highlight",first,50,700,"disconnected selection");
            float x=50+width(first+gap),right=x+width(last);float[] q=Arrays.copyOf(a.getQuadPoints(),16);
            float[] tail={x,712,right,712,x,698,right,698};System.arraycopy(tail,0,q,8,8);a.setQuadPoints(q);
            d.save(dir.resolve("disconnected.pdf").toFile());
        }
        Files.move(dir.resolve("disconnected.pdf"),old(),StandardCopyOption.REPLACE_EXISTING);
        assertEquals("unsupported",analyze().entries().get(0).status());
    }

}
