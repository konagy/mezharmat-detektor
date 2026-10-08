package org.konagy.honeydew;

import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.javacpp.indexer.*;
import ij.process.*;
import java.awt.image.BufferedImage;
import java.util.*;
import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_imgproc.*;
import static org.bytedeco.opencv.global.opencv_video.*;
import static org.konagy.honeydew.Project.*;

/** Sparse forward/backward LK flow with robust similarity consensus and explicit loss of tracking. */
final class Tracking implements AutoCloseable {
    record Pose(Point head, Point tail, double confidence, boolean valid, String reason) {
        double length() { return head.distance(tail); }
    }
    record Pair(Point from, Point to) {}
    record Transform(double a,double b,double tx,double ty) {
        Point apply(Point p) { return new Point(a*p.x()-b*p.y()+tx,b*p.x()+a*p.y()+ty); }
        double scale() { return Math.hypot(a,b); }
    }
    private Mat previous, features;
    private Pose pose;
    private double time;
    Tracking() { setNumThreads(1); }
    static ByteProcessor gray(BufferedImage image) {
        return (ByteProcessor)new ColorProcessor(image).convertToByte(false);
    }
    private static Mat mat(ByteProcessor gray) {
        Mat result=new Mat(gray.getHeight(),gray.getWidth(),CV_8UC1);
        result.data().put((byte[])gray.getPixels());
        return result;
    }
    Pose reset(ByteProcessor image, Anchor anchor, double seconds) {
        close();
        pose=new Pose(anchor.head(),anchor.tail(),1,true,"Manual anchor");
        if (!inside(anchor.head(),image) || !inside(anchor.tail(),image)) {
            pose=new Pose(anchor.head(),anchor.tail(),0,false,"Anchor is outside the image");
            return pose;
        }
        previous=mat(image); features=select(previous,pose,anchor.body()); time=seconds;
        if(features.rows()<6) lose("Too few body features; choose a clearer anchor");
        return pose;
    }
    private static boolean inside(Point p,ByteProcessor ip) { return p.x()>=0 && p.y()>=0 && p.x()<ip.getWidth() && p.y()<ip.getHeight(); }
    Pose update(ByteProcessor image,double seconds) {
        if(pose==null || !pose.valid()) return pose;
        if(seconds<=time || seconds-time>0.25) return lose("Timestamp discontinuity; manual correction required");
        try(Mat current=mat(image); Mat next=new Mat(); Mat back=new Mat(); Mat ok=new Mat(); Mat okBack=new Mat(); Mat error=new Mat(); Mat backError=new Mat()) {
            calcOpticalFlowPyrLK(previous,current,features,next,ok,error);
            calcOpticalFlowPyrLK(current,previous,next,back,okBack,backError);
            List<Pair> pairs=new ArrayList<>();
            try(FloatIndexer p=features.createIndexer(); FloatIndexer q=next.createIndexer(); FloatIndexer r=back.createIndexer();
                UByteIndexer status=ok.createIndexer(); UByteIndexer statusBack=okBack.createIndexer(); FloatIndexer errors=error.createIndexer()) {
                for(int i=0;i<features.rows();i++) {
                    Point from=new Point(p.get(i,0,0),p.get(i,0,1));
                    Point to=new Point(q.get(i,0,0),q.get(i,0,1));
                    Point returned=new Point(r.get(i,0,0),r.get(i,0,1));
                    if(status.get(i,0)!=0 && statusBack.get(i,0)!=0 && from.distance(returned)<1.5 &&
                       errors.get(i,0)<25 && inside(to,image)) pairs.add(new Pair(from,to));
                }
            }
            Transform t=consensus(pairs,Math.max(1.5,pose.length()*0.015));
            if(t==null) return lose("Inconsistent body motion; manual correction required");
            double threshold=Math.max(1.5,pose.length()*0.015);
            long count=pairs.stream().filter(p->t.apply(p.from).distance(p.to)<threshold).count();
            double quality=(double)count/Math.max(1,features.rows());
            Point head=t.apply(pose.head),tail=t.apply(pose.tail);
            if(quality<0.45 || count<6 || t.scale()<0.92 || t.scale()>1.08 ||
                Math.abs(Math.atan2(t.b,t.a))>0.24 || tail.distance(pose.tail)>pose.length()*0.30 ||
                !inside(head,image) || !inside(tail,image))
                return lose("Unreliable body pose; manual correction required");
            Pose proposed=new Pose(head,tail,quality,true,"Tracked");
            Mat selected=select(current,proposed,null);
            if(selected.rows()<6) { selected.close(); return lose("Body texture lost; manual correction required"); }
            previous.close(); previous=current.clone();
            features.close(); features=selected; pose=proposed; time=seconds;
            return pose;
        }
    }
    private Pose lose(String reason) { pose=new Pose(pose.head,pose.tail,0,false,reason); return pose; }
    private static Mat select(Mat image,Pose pose,Box initialBox) {
        try(Mat mask=new Mat(image.rows(),image.cols(),CV_8UC1)) {
            mask.data().put(new byte[image.rows()*image.cols()]);
            double length=pose.length(), ux=(pose.head.x()-pose.tail.x())/length,uy=(pose.head.y()-pose.tail.y())/length;
            int x0=(int)Math.max(0,Math.min(pose.head.x(),pose.tail.x())-length*.3);
            int x1=(int)Math.min(image.cols()-1,Math.max(pose.head.x(),pose.tail.x())+length*.3);
            int y0=(int)Math.max(0,Math.min(pose.head.y(),pose.tail.y())-length*.3);
            int y1=(int)Math.min(image.rows()-1,Math.max(pose.head.y(),pose.tail.y())+length*.3);
            try(UByteIndexer ix=mask.createIndexer()) {
                for(int y=y0;y<=y1;y++) for(int x=x0;x<=x1;x++) {
                    double dx=x-pose.tail.x(),dy=y-pose.tail.y(),u=(dx*ux+dy*uy)/length,v=(-dx*uy+dy*ux)/length;
                    boolean inBox=initialBox==null || (x>=initialBox.x() && x<initialBox.x()+initialBox.width() && y>=initialBox.y() && y<initialBox.y()+initialBox.height());
                    if(inBox && u>.12 && u<.95 && Math.abs(v)<.24) ix.put(y,x,255);
                }
            }
            Mat points=new Mat();
            goodFeaturesToTrack(image,points,80,.015,Math.max(3,length*.035),mask,3,false,.04);
            return points;
        }
    }
    static Transform consensus(List<Pair> pairs,double threshold) {
        if(pairs.size()<6) return null;
        List<Pair> best=List.of();
        // Deterministic two-point hypotheses keep runs reproducible.
        for(int i=0;i<pairs.size();i+=2) for(int j=i+1;j<pairs.size();j+=3) {
            if(pairs.get(i).from.distance(pairs.get(j).from)<8) continue;
            Transform t=fit(List.of(pairs.get(i),pairs.get(j)));
            if(t==null || t.scale()<.85 || t.scale()>1.15) continue;
            List<Pair> in=new ArrayList<>();
            for(Pair p:pairs) if(t.apply(p.from).distance(p.to)<threshold) in.add(p);
            if(in.size()>best.size()) best=in;
        }
        return best.size()>=6 && best.size()>=pairs.size()*.6 ? fit(best):null;
    }
    static Transform fit(List<Pair> pairs) {
        double px=0,py=0,qx=0,qy=0;
        for(Pair p:pairs) {px+=p.from.x();py+=p.from.y();qx+=p.to.x();qy+=p.to.y();}
        int n=pairs.size(); px/=n;py/=n;qx/=n;qy/=n;
        double den=0,a=0,b=0;
        for(Pair p:pairs) {
            double x=p.from.x()-px,y=p.from.y()-py,u=p.to.x()-qx,v=p.to.y()-qy;
            den+=x*x+y*y;a+=x*u+y*v;b+=x*v-y*u;
        }
        if(den<1e-9) return null;
        a/=den;b/=den;
        return new Transform(a,b,qx-a*px+b*py,qy-b*px-a*py);
    }
    @Override public void close() {
        if(previous!=null) {previous.close();previous=null;}
        if(features!=null) {features.close();features=null;}
    }
}
