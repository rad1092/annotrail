package net.whago.annotrail;

import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.interactive.annotation.*;

/** Synthetic benchmark; every generated PDF and report is deleted before return. */
public final class Benchmark {
 public static void main(String[] args) throws Exception {
  Path parent=Path.of(args[0]); int pages=args.length>1?Integer.parseInt(args[1]):100;
  Path work=Files.createTempDirectory(parent,"annotrail-benchmark-");
  try {
   Path old=work.resolve("old.pdf"), newer=work.resolve("new.pdf");
   fixture(old,pages,false);fixture(newer,pages,true);
   long inputBytes=Files.size(old)+Files.size(newer);
   long start=System.nanoTime();
   var plan=Rebaser.analyze(old,newer,Models.Options.defaults(),()->false);
   Map<String,Integer> choices=new LinkedHashMap<>();
   for(var e:plan.entries()) { if(!e.status().equals("confident"))throw new AssertionError(e.status()+" "+e.reason());choices.put(e.id(),0); }
   var result=Rebaser.export(old,newer,plan,choices,work.resolve("out.pdf"),work.resolve("report.json"),()->false);
   if(result.transferred()!=pages)throw new AssertionError(result);
   double elapsed=(System.nanoTime()-start)/1e9;
   System.out.println(Main.JSON.toJson(Map.of("pages_per_pdf",pages,"annotations",pages,"total_input_bytes",inputBytes,"analysis_reanalysis_export_seconds",elapsed,"transferred",result.transferred(),"originals_verified_by_engine",true,"synthetic_bulk_deleted",true,"java_version",System.getProperty("java.version"))));
  } finally {
   try(var files=Files.list(work)){for(Path p:files.toList())Files.delete(p);}Files.delete(work);
  }
 }
 private static void fixture(Path path,int pages,boolean revised)throws Exception {
  try(PDDocument d=new PDDocument()){
   PDFont font=new PDType1Font(Standard14Fonts.FontName.HELVETICA);
   for(int p=0;p<pages;p++) {
    PDPage page=new PDPage(PDRectangle.LETTER);d.addPage(page);
    String anchor=String.format("Transfer this exact annotation for document page %04d.",p);
    float x=revised?80:60,y=revised?700:740;
    try(PDPageContentStream s=new PDPageContentStream(d,page)) {
     s.beginText();s.setFont(font,11);s.newLineAtOffset(x,y);s.showText(anchor);s.endText();
     for(int line=0;line<45;line++) {s.beginText();s.setFont(font,9);s.newLineAtOffset(60,650-line*12);s.showText("Supporting paragraph number "+line+" retains ordinary selectable text in this synthetic page.");s.endText();}
    }
    if(!revised) {
     float w=font.getStringWidth(anchor)/1000*11;
     PDAnnotationHighlight a=new PDAnnotationHighlight();a.setContents("Review this finding.");
     a.setQuadPoints(new float[]{x,y+11,x+w,y+11,x,y-2,x+w,y-2});a.setRectangle(new PDRectangle(x,y-2,w,13));page.getAnnotations().add(a);
    }
   }
   d.save(path.toFile());
  }
 }
}
