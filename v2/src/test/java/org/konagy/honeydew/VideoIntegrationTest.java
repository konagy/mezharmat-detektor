package org.konagy.honeydew;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.bytedeco.javacv.*;
import java.nio.file.*;
import java.awt.image.BufferedImage;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VideoIntegrationTest {
    @TempDir Path temporary;
    @Test void variablePresentationTimesSurviveDecodeAndSeek() throws Exception {
        Path file=temporary.resolve("variable.mp4");
        long[] times={0,33333,100000,133333,266667,300000};
        try(FFmpegFrameRecorder recorder=new FFmpegFrameRecorder(file.toFile(),96,64);
            Java2DFrameConverter converter=new Java2DFrameConverter()){
            recorder.setVideoCodec(org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_MPEG4);
            recorder.setFormat("mp4");recorder.setFrameRate(30);
            recorder.setVideoOption("threads","1");recorder.start();
            for(int i=0;i<times.length;i++){
                BufferedImage image=new BufferedImage(96,64,BufferedImage.TYPE_INT_RGB);
                var g=image.createGraphics();g.setColor(new java.awt.Color(30+i*30,80,100));g.fillRect(0,0,96,64);g.dispose();
                recorder.setTimestamp(times[i]);recorder.record(converter.convert(image));
            }
            recorder.stop();
        }
        try(Video video=new Video(file)){
            List<Double> decoded=new ArrayList<>();Video.Sample frame;
            while((frame=video.next())!=null)decoded.add(frame.seconds());
            assertEquals(times.length,decoded.size());
            for(int i=0;i<times.length;i++)assertEquals(times[i]/1e6,decoded.get(i),.002);
            assertTrue(decoded.get(4)-decoded.get(3)>.1,"Must not substitute nominal frame/fps.");
            Video.Sample sought=video.seek(.1);
            assertNotNull(sought);assertEquals(.1,sought.seconds(),.002);
        }
    }
}
