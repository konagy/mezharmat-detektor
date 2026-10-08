package org.konagy.honeydew;

import ij.process.*;
import hu.elte.animaltracker.model.tracking.blobs.BaseBlob;
import java.util.*;
import static org.konagy.honeydew.Project.*;

/** Body-relative, polarity-independent temporal segmentation. Outputs hypotheses, not diagnoses. */
final class Detector {
    record Result(boolean assessed,String reason) {}
    private record Spot(double u,double v,double area,double contrast,Point global) {}
    private static final class Track {
        double start,last,peak,peakEvidence,u,v,area; Point global; int observations;
        final boolean bornAtTip;
        Track(double t,Spot s,boolean bornAtTip) {this.bornAtTip=bornAtTip;start=last=peak=t;update(t,s);}
        void update(double t,Spot s) {
            last=t;u=s.u;v=s.v;area=s.area;observations++;
            if(s.contrast>peakEvidence) {peakEvidence=s.contrast;peak=t;global=s.global;}
        }
    }
    private final Settings settings;
    private final Components components=new Components();
    private final List<Track> active=new ArrayList<>();
    private final List<Event> events;
    private float[] background;
    private float[] sceneBackground;
    private double sceneTime;
    private float[] changeAge;
    private boolean[] visible, previousVisible;
    private int width,height;
    private double started,lastTime,baseLength;
    Detector(Settings settings,List<Event> events) {this.settings=settings;this.events=events;}
    void reset(double seconds) {flush();background=null;sceneBackground=null;changeAge=null;previousVisible=null;started=lastTime=seconds;}
    Result process(ByteProcessor gray,Tracking.Pose pose,double seconds) {
        if(pose==null || !pose.valid()) {reset(seconds);return new Result(false,"Tracking unavailable");}
        if(sceneBackground==null) {
            sceneBackground=new float[gray.getWidth()*gray.getHeight()];
            byte[] pixels=(byte[])gray.getPixels();
            for(int i=0;i<pixels.length;i++)sceneBackground[i]=pixels[i]&255;
            sceneTime=seconds;
        }
        try {return evaluate(gray,pose,seconds);}
        finally {
            if(sceneBackground!=null) {
                double alpha=1-Math.exp(-Math.max(0,seconds-sceneTime)/.6);
                byte[] pixels=(byte[])gray.getPixels();
                for(int i=0;i<pixels.length;i++)sceneBackground[i]+=(float)(((pixels[i]&255)-sceneBackground[i])*alpha);
                sceneTime=seconds;
            }
        }
    }
    private Result evaluate(ByteProcessor gray,Tracking.Pose pose,double seconds) {
        if(background==null) {
            baseLength=pose.length();
            width=(int)Math.max(24,Math.min(256,Math.ceil(baseLength*.8)));
            height=(int)Math.max(24,Math.min(256,Math.ceil(baseLength*.7)));
            background=patch(gray,pose);
            changeAge=new float[width*height];
            previousVisible=visible.clone();
            started=lastTime=seconds;
            return new Result(false,background==null?"Tail region outside the image":"Background warm-up");
        }
        if(seconds<=lastTime || seconds-lastTime>.25) {reset(seconds);return new Result(false,"Timestamp discontinuity");}
        float[] values=patch(gray,pose);
        if(values==null) {reset(seconds);return new Result(false,"Tail region outside the image");}
        float[] scene=patch(new FloatProcessor(gray.getWidth(),gray.getHeight(),sceneBackground),pose);
        for(int i=0;i<values.length;i++) if(visible[i] && !previousVisible[i]) background[i]=values[i];
        previousVisible=visible.clone();
        double dt=seconds-lastTime;lastTime=seconds;
        if(seconds-started<settings.warmupSeconds) {
            for(int i=0;i<values.length;i++) background[i]+=(values[i]-background[i])*.25f;
            return new Result(false,"Background warm-up");
        }
        double[] residual=new double[values.length];
        for(int i=0;i<values.length;i++) residual[i]=visible[i]?values[i]-background[i]:0;
        double offset=median(residual),mad;
        double[] absolute=new double[values.length];
        for(int i=0;i<values.length;i++) absolute[i]=Math.abs(residual[i]-offset);
        mad=median(absolute);
        double threshold=Math.max(settings.minContrast,settings.noiseMultiplier*1.4826*mad);
        double[] sceneResidual=new double[values.length];
        for(int i=0;i<values.length;i++)sceneResidual[i]=visible[i]?values[i]-scene[i]:0;
        double sceneOffset=median(sceneResidual);
        for(int i=0;i<values.length;i++)sceneResidual[i]=Math.abs(sceneResidual[i]-sceneOffset);
        double sceneThreshold=Math.max(settings.minContrast,settings.noiseMultiplier*1.4826*median(sceneResidual));
        boolean[] mask=new boolean[values.length];
        int foreground=0;
        for(int y=0;y<height;y++) for(int x=0;x<width;x++) {
            int i=y*width+x;
            double u=(double)x/(width-1)*.8-.12,v=(double)y/(height-1)*.7-.35;
            // Keep the immediate tip and outside-body region; do not use global-motion vetoes.
            // A static leaf can move in body coordinates. Require novelty in both coordinate systems.
            mask[i]=visible[i] && (u>-.025 || Math.abs(v)>.22) && absolute[i]>threshold && sceneResidual[i]>sceneThreshold;
            if(mask[i]) foreground++;
        }
        if(foreground>values.length*.22) {
            flush(); background=null;
            return new Result(false,"Large tail-region change; background must recover");
        }
        List<Spot> spots=new ArrayList<>();
        for(BaseBlob blob:components.extract(mask,width,height)) {
            var bounds=blob.getBound();
            int area=blob.getSize();
            double normalizedArea=area/(double)(width*height)*.56;
            if(area<3 || normalizedArea>.025 || bounds.width>width*.40 || bounds.height>height*.46) continue;
            if(Math.min(bounds.width,bounds.height)<2) continue;
            if(Math.max(bounds.width,bounds.height)>5*Math.min(bounds.width,bounds.height)) continue;
            double u=blob.getCoM().x/(width-1)*.8-.12,v=blob.getCoM().y/(height-1)*.7-.35;
            double evidence=0; boolean[] pixels=blob.getMask();
            // Iterate mask/bounds: upstream BaseBlob.getPoints() has a coordinate bug.
            for(int i=0;i<pixels.length;i++) if(pixels[i]) evidence+=absolute[(bounds.y+i/bounds.width)*width+bounds.x+i%bounds.width];
            evidence/=Math.max(1,area);
            spots.add(new Spot(u,v,area,evidence,toGlobal(pose,u,v)));
        }
        associate(spots,seconds);
        double alpha=1-Math.exp(-dt/1.5);
        for(int i=0;i<values.length;i++) {
            changeAge[i]=absolute[i]>=threshold?changeAge[i]+(float)dt:0;
            // Freeze brief novelties, but do not preserve a changed leaf edge as a permanent ghost.
            double rate=changeAge[i]>.25?1-Math.exp(-dt/.15):alpha;
            if(absolute[i]<threshold || changeAge[i]>.25) background[i]+=(float)((values[i]-background[i])*rate);
        }
        return new Result(true,"Assessed");
    }
    private float[] patch(ImageProcessor gray,Tracking.Pose pose) {
        float[] result=new float[width*height];
        visible=new boolean[result.length];int valid=0;
        for(int y=0;y<height;y++) for(int x=0;x<width;x++) {
            Point p=toGlobal(pose,(double)x/(width-1)*.8-.12,(double)y/(height-1)*.7-.35);
            if(p.x()<2 || p.y()<2 || p.x()>=gray.getWidth()-3 || p.y()>=gray.getHeight()-3) continue;
            visible[y*width+x]=true;valid++;
            result[y*width+x]=(float)gray.getInterpolatedValue(p.x(),p.y());
        }
        // A clipped far corner must not invalidate a fully visible abdomen tip.
        if(valid<result.length*.85 || pose.tail().x()<pose.length()*.2 || pose.tail().y()<pose.length()*.2 ||
            pose.tail().x()>gray.getWidth()-pose.length()*.2 || pose.tail().y()>gray.getHeight()-pose.length()*.2) return null;
        FloatProcessor fp=new FloatProcessor(width,height,result);
        fp.blurGaussian(.65);
        return (float[])fp.getPixels();
    }
    private static Point toGlobal(Tracking.Pose p,double u,double v) {
        double dx=p.tail().x()-p.head().x(),dy=p.tail().y()-p.head().y();
        return new Point(p.tail().x()+u*dx-v*dy,p.tail().y()+u*dy+v*dx);
    }
    private void associate(List<Spot> spots,double time) {
        active.removeIf(t->{if(time-t.last>settings.maxGapSeconds) {finish(t);return true;} return false;});
        Set<Track> used=new HashSet<>();
        spots.sort(Comparator.comparingDouble(Spot::contrast).reversed());
        for(Spot spot:spots) {
            Track best=null;double distance=.16;
            for(Track t:active) if(!used.contains(t)) {
                double d=Math.hypot(t.u-spot.u,t.v-spot.v);
                if(d<distance && spot.area/t.area>.2 && spot.area/t.area<5) {best=t;distance=d;}
            }
            if(best!=null) {best.update(time,spot);used.add(best);}
            else if(active.size()<64) {
                // Follow incoming objects as context; entering the tip region is not a new birth.
                Track t=new Track(time,spot,spot.u>.015 && Math.hypot(spot.u,spot.v)<.20);
                active.add(t);used.add(t);
            }
        }
    }
    private void finish(Track t) {
        if(t.bornAtTip && t.observations>=2 && t.last-t.start>=settings.minVisibleSeconds) {
            if(events.size()>=50_000) throw new IllegalStateException("Candidate safety limit reached. Analyze shorter segments or adjust settings.");
            Event event=new Event(t.start,t.last,t.peak,t.global.x(),t.global.y(),t.peakEvidence);
            event.reason=t.last-t.start>2 ? "Persistent tail change; ambiguous object" : "New persistent tail-local contrast; verify excretion";
            events.add(event);
        }
    }
    void flush() {for(Track t:active) finish(t);active.clear();}
    static double median(double[] input) {
        double[] a=input.clone();Arrays.sort(a);
        return a.length%2==0 ? (a[a.length/2-1]+a[a.length/2])/2:a[a.length/2];
    }
}
