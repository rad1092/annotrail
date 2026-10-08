package net.whago.annotrail;

import static net.whago.annotrail.FixtureGenerator.*;
import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import net.whago.annotrail.Models.*;
import org.apache.pdfbox.Loader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublicationExportTest {
    @TempDir Path dir;
    private Path old(){return dir.resolve("old.pdf");}
    private Path revised(){return dir.resolve("new.pdf");}
    private Map<String,Integer> choices(Plan plan){
        Map<String,Integer> choices=new LinkedHashMap<>();
        for(Entry entry:plan.entries())choices.put(entry.id(),entry.status().equals("confident")?0:-1);
        return choices;
    }
    private final OutputPublication.FileOperations noLinks=new OutputPublication.FileOperations(){
        @Override void createLink(Path output,Path staged)throws IOException {
            throw new FileSystemException(output.toString(),staged.toString(),"Operation not supported");
        }
    };
    private void noTemps()throws IOException {
        try(var files=Files.list(dir)){assertFalse(files.anyMatch(p->p.getFileName().toString().startsWith(".annotrail-")));}
    }
    @Test void fullPdfAndReportExportWorksWithoutHardLinks()throws Exception {
        generate(dir);byte[] beforeOld=Files.readAllBytes(old()),beforeNew=Files.readAllBytes(revised());
        Plan plan=Rebaser.analyze(old(),revised(),Options.defaults(),()->false);
        Path output=dir.resolve("out.pdf"),report=dir.resolve("report.json");
        Result result=Rebaser.export(old(),revised(),plan,choices(plan),output,report,()->false,noLinks);
        assertEquals(1,result.transferred());assertTrue(Files.readString(report).contains("Preserve numerical evidence."));
        try(var pdf=Loader.loadPDF(output.toFile())){assertEquals(1,pdf.getPage(1).getAnnotations().size());}
        assertArrayEquals(beforeOld,Files.readAllBytes(old()));assertArrayEquals(beforeNew,Files.readAllBytes(revised()));noTemps();
    }
    @Test void fallbackCancellationAfterPdfReservationRollsBackBothFiles()throws Exception {
        generate(dir);Plan plan=Rebaser.analyze(old(),revised(),Options.defaults(),()->false);
        Path output=dir.resolve("out.pdf"),report=dir.resolve("report.json");
        assertThrows(InterruptedIOException.class,()->Rebaser.export(old(),revised(),plan,choices(plan),output,report,()->Files.exists(output),noLinks));
        assertFalse(Files.exists(output));assertFalse(Files.exists(report));noTemps();
    }
    @Test void fallbackLateReportCollisionPreservesUnrelatedReportAndRollsBackPdf()throws Exception {
        generate(dir);Plan plan=Rebaser.analyze(old(),revised(),Options.defaults(),()->false);
        Path output=dir.resolve("out.pdf"),report=dir.resolve("report.json");
        BooleanSupplier collision=()->{
            if(Files.exists(output) && !Files.exists(report))try{Files.writeString(report,"external report",StandardOpenOption.CREATE_NEW);}catch(IOException failure){throw new UncheckedIOException(failure);}
            return false;
        };
        assertThrows(IOException.class,()->Rebaser.export(old(),revised(),plan,choices(plan),output,report,collision,noLinks));
        assertFalse(Files.exists(output));assertEquals("external report",Files.readString(report));noTemps();
    }
    @Test void fallbackCancellationDuringReportCopyRollsBackBothFiles()throws Exception {
        generate(dir);Plan plan=Rebaser.analyze(old(),revised(),Options.defaults(),()->false);
        Path output=dir.resolve("out.pdf"),report=dir.resolve("report.json");
        assertThrows(InterruptedIOException.class,()->Rebaser.export(old(),revised(),plan,choices(plan),output,report,()->Files.exists(report),noLinks));
        assertFalse(Files.exists(output));assertFalse(Files.exists(report));noTemps();
    }
}
