package net.whago.annotrail;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.graphics.color.*;
import org.apache.pdfbox.pdmodel.interactive.annotation.*;

/** Small synthetic PDFs, generated without personal data or checked-in binaries. */
public final class FixtureGenerator {
    static final PDType1Font FONT = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    public static final String UNIQUE = "The measured sample contains forty two stable particles.";
    public static final String DUPLICATE = "The repeated reference has the same exact wording.";
    public static final String MISSING = "This earlier passage does not occur in the revised document.";
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Usage: FixtureGenerator OUTPUT_DIRECTORY");
        generate(Path.of(args[0]));
    }
    public static void generate(Path directory)throws IOException {
        Files.createDirectories(directory);
        Path old=directory.resolve("old.pdf"), revised=directory.resolve("new.pdf");
        if(Files.exists(old)||Files.exists(revised))throw new IOException("Fixture files already exist.");
        try(PDDocument doc=new PDDocument()) {
            PDPage page=page(doc);
            line(doc,page,UNIQUE,50,730); mark(page,"Highlight",UNIQUE,50,730,"Preserve numerical evidence.");
            line(doc,page,DUPLICATE,50,680); mark(page,"Underline",DUPLICATE,50,680,"Choose the correct repeated section.");
            line(doc,page,MISSING,50,630); mark(page,"StrikeOut",MISSING,50,630,"Retain this unresolved note in the report.");
            PDAnnotationInk ink=new PDAnnotationInk(); ink.setRectangle(new PDRectangle(50,560,60,30));
            ink.setInkList(new float[][]{new float[]{50,560,80,585,110,560}}); ink.setContents("Unsupported handwriting survives in the report.");
            page.getAnnotations().add(ink); doc.save(old.toFile());
        }
        try(PDDocument doc=new PDDocument()) {
            PDPage first=page(doc); line(doc,first,"A new preface shifts the original content to page two.",50,740);
            PDPage second=page(doc); line(doc,second,UNIQUE,70,660); line(doc,second,DUPLICATE,70,600);
            line(doc,second,DUPLICATE,70,520);
            PDAnnotationText note=new PDAnnotationText(); note.setRectangle(new PDRectangle(30,730,20,20)); note.setContents("Existing revised annotation.");
            first.getAnnotations().add(note); doc.save(revised.toFile());
        }
    }
    static PDPage page(PDDocument doc) { PDPage p=new PDPage(PDRectangle.LETTER);doc.addPage(p);return p; }
    static void line(PDDocument doc,PDPage page,String text,float x,float y)throws IOException {
        try(PDPageContentStream c=new PDPageContentStream(doc,page,PDPageContentStream.AppendMode.APPEND,true)) {
            c.beginText();c.setFont(FONT,12);c.newLineAtOffset(x,y);c.showText(text);c.endText();
        }
    }
    static float width(String text)throws IOException{return FONT.getStringWidth(text)/1000*12;}
    static PDAnnotationTextMarkup mark(PDPage page,String type,String text,float x,float y,String comment)throws IOException {
        PDAnnotationTextMarkup a=switch(type){case "Underline"->new PDAnnotationUnderline();case "StrikeOut"->new PDAnnotationStrikeout();default->new PDAnnotationHighlight();};
        float w=width(text); a.setQuadPoints(new float[]{x,y+12,x+w,y+12,x,y-2,x+w,y-2});
        a.setRectangle(new PDRectangle(x,y-2,w,14));a.setContents(comment);a.setTitlePopup("Synthetic reviewer");
        a.setColor(new PDColor(new float[]{1,0.8f,0},PDDeviceRGB.INSTANCE));a.setConstantOpacity(0.6f);
        page.getAnnotations().add(a);return a;
    }
}
