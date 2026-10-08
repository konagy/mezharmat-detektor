package org.konagy.honeydew;

import java.nio.file.Path;
import java.util.*;
import java.util.function.*;
import static org.konagy.honeydew.Project.*;

final class Analysis {
    record Progress(double seconds,double duration,long frames,int candidates,String tracking) {}
    static Project run(Project source,BooleanSupplier canceled,Consumer<Progress> progress) throws Exception {
        ij.Prefs.setThreads(1);
        source.validate();
        if(source.anchors.isEmpty()) throw new IllegalArgumentException("Mark the body, head and abdomen tip before analysis.");
        Project out=source.copy();out.candidates.clear();out.unassessed.clear();
        out.detectorVersion="dual-background-context/0.1";
        out.analyzedAtUtc=java.time.Instant.now().toString();
        out.runStatus="Running";out.analyzedUntil=0;out.processedFrames=0;
        long started=System.nanoTime();int anchorIndex=0;
        Detector detector=new Detector(out.settings,out.candidates);
        double prior=Double.NaN;
        try(Video video=new Video(Path.of(out.video));Tracking tracking=new Tracking()) {
            Video.Sample frame;
            boolean initialized=false;
            while(!canceled.getAsBoolean() && (frame=video.next())!=null) {
                double seconds=frame.seconds();
                if(!Double.isFinite(seconds) || seconds<0) throw new IllegalStateException("Invalid presentation timestamp.");
                var gray=Tracking.gray(frame.image());
                Tracking.Pose pose=null;
                if(anchorIndex<out.anchors.size() && out.anchors.get(anchorIndex).seconds()<=seconds+0.0005) {
                    Anchor anchor=out.anchors.get(anchorIndex++);
                    // An anchor must match the actual decoded frame, not an arbitrary elapsed time.
                    if(Math.abs(anchor.seconds()-seconds)>.02) throw new IllegalStateException("Anchor does not match a decoded frame at "+anchor.seconds()+" s.");
                    pose=tracking.reset(gray,anchor,seconds);detector.reset(seconds);initialized=true;
                } else if(initialized) pose=tracking.update(gray,seconds);
                Detector.Result result=detector.process(gray,pose,seconds);
                if(!result.assessed()) addInterval(out.unassessed,Double.isNaN(prior)?0:prior,seconds,
                    pose!=null && !pose.valid()?pose.reason():result.reason());
                out.analyzedUntil=seconds;out.processedFrames++;prior=seconds;
                if(out.processedFrames%15==0) progress.accept(new Progress(seconds,video.duration(),out.processedFrames,out.candidates.size(),pose==null?"Awaiting anchor":pose.reason()));
            }
            detector.flush();
            out.runStatus=canceled.getAsBoolean()?"Canceled — incomplete":"Complete — candidates require review";
            if(!canceled.getAsBoolean() && video.duration()>out.analyzedUntil+.1)
                addInterval(out.unassessed,out.analyzedUntil,video.duration(),"No decoded images in trailing interval");
        }
        out.candidates.sort(Comparator.comparingDouble(e->e.start));
        out.wallSeconds=(System.nanoTime()-started)/1e9;
        return out;
    }
    static void addInterval(List<Interval> list,double start,double end,String reason) {
        if(!list.isEmpty()) {
            Interval last=list.getLast();
            if(last.reason().equals(reason) && start<=last.end()+.001) {
                list.set(list.size()-1,new Interval(last.start(),Math.max(last.end(),end),reason));return;
            }
        }
        list.add(new Interval(start,end,reason));
    }
}
