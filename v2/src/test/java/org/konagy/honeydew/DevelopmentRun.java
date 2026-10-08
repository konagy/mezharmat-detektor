package org.konagy.honeydew;

import java.nio.file.*;
import java.util.Locale;
import static org.konagy.honeydew.Project.*;

/** Development fixtures only. Approximate user hints are deliberately not treated as ground truth. */
public final class DevelopmentRun {
    public static void main(String[] args) throws Exception {
        try {
        org.bytedeco.ffmpeg.global.avutil.av_log_set_level(org.bytedeco.ffmpeg.global.avutil.AV_LOG_ERROR);
        Path sources=Path.of(args[0]),output=Path.of(args[1]);Files.createDirectories(output);
        double[][] coordinates={
            {698,402,172,126,850,478,704,448},
            {121,500,191,125,299,523,158,612},
            {137,505,191,120,306,533,163,617},
            {355,490,181,112,512,505,390,589},
            {355,490,181,112,512,505,390,589}
        };
        for(int i=0;i<coordinates.length;i++){
            double[] c=coordinates[i];Project p=Project.create(sources.resolve((i+1)+".mp4"));
            double first;
            try(Video v=new Video(Path.of(p.video))){first=v.next().seconds();}
            p.setAnchor(new Anchor(first,new Box(c[0],c[1],c[2],c[3]),new Point(c[4],c[5]),new Point(c[6],c[7])));
            p.save(output.resolve("short-"+(i+1)+"-input.json"));
            Project result=Analysis.run(p,()->false,progress->{});
            result.save(output.resolve("short-"+(i+1)+"-result.json"));
            result.exportCsv(output.resolve("short-"+(i+1)+"-candidates.csv"));
            double unassessed=result.unassessed.stream().mapToDouble(x->x.end()-x.start()).sum();
            System.out.printf(Locale.ROOT,"short_%d frames=%d seconds=%.3f candidates=%d unassessed_seconds=%.3f wall_seconds=%.3f%n",i+1,result.processedFrames,result.analyzedUntil,result.candidates.size(),unassessed,result.wallSeconds);
        }
        } finally {App.shutdownImageJ();}
    }
}
