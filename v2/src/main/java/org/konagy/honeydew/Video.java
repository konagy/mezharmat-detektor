package org.konagy.honeydew;

import org.bytedeco.javacv.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

/** Decodes one image at a time. Time comes from FFmpeg presentation timestamps, never frame/fps. */
public final class Video implements AutoCloseable {
    private final FFmpegFrameGrabber grabber;
    private final Java2DFrameConverter converter = new Java2DFrameConverter();
    public record Sample(double seconds, BufferedImage image) {}
    public Video(Path path) throws Exception {
        grabber = new FFmpegFrameGrabber(path.toFile());
        grabber.setVideoOption("threads","1");
        grabber.setAudioChannels(0);
        try { grabber.start(); } catch(Exception e) { grabber.close(); throw e; }
    }
    public double duration() { return grabber.getLengthInTime()/1_000_000.0; }
    public double fps() { return grabber.getFrameRate(); }
    public Sample next() throws Exception {
        Frame frame=grabber.grabImage();
        if(frame==null) return null;
        BufferedImage image=Java2DFrameConverter.cloneBufferedImage(converter.convert(frame));
        return new Sample(frame.timestamp/1_000_000.0,image);
    }
    public Sample seek(double seconds) throws Exception {
        grabber.setVideoTimestamp(Math.round(Math.max(0,seconds)*1_000_000));
        return next();
    }
    @Override public void close() throws Exception { try { grabber.close(); } finally { converter.close(); } }
}
