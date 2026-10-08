package org.konagy.honeydew;
import java.nio.file.*;
import java.util.Locale;
public final class TrackDiagnostics {
    public static void main(String[] args) throws Exception {
        org.bytedeco.ffmpeg.global.avutil.av_log_set_level(org.bytedeco.ffmpeg.global.avutil.AV_LOG_ERROR);
        Project p=Project.load(Path.of(args[0]));
        try(Video video=new Video(Path.of(p.video));Tracking tracking=new Tracking()){
            Video.Sample frame;int count=0;
            while((frame=video.next())!=null){
                var gray=Tracking.gray(frame.image());
                var pose=count==0?tracking.reset(gray,p.anchors.getFirst(),frame.seconds()):tracking.update(gray,frame.seconds());
                if(count++%30==0)System.out.printf(Locale.ROOT,"%.3f tail=(%.2f,%.2f) head=(%.2f,%.2f) length=%.2f confidence=%.2f %s%n",frame.seconds(),pose.tail().x(),pose.tail().y(),pose.head().x(),pose.head().y(),pose.length(),pose.confidence(),pose.reason());
            }
        }
    }
}
