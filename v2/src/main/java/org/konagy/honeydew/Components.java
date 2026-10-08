package org.konagy.honeydew;

import hu.elte.animaltracker.model.CustomisableProcess;
import hu.elte.animaltracker.model.tracking.CoreTracker;
import hu.elte.animaltracker.model.tracking.blobdetecting.BaseBlobDetector;
import hu.elte.animaltracker.model.tracking.blobs.BaseBlob;
import hu.elte.animaltracker.model.tracking.thresholding.*;
import ij.ImagePlus;
import ij.process.*;
import java.util.List;

/** Headless adapter to the real AnimalTracker segmentation pipeline, on bounded local images. */
final class Components {
    private final CoreTracker core=new CoreTracker();
    Components() { core.setBlobDetector(new BaseBlobDetector()); core.setThresholder(new BinaryThreshold()); }
    List<BaseBlob> extract(boolean[] mask,int width,int height) {
        byte[] pixels=new byte[mask.length];
        for(int i=0;i<pixels.length;i++) if(mask[i]) pixels[i]=(byte)255;
        return core.getBlobs(new ByteProcessor(width,height,pixels),false);
    }
    static final class BinaryThreshold extends AbstractThresholder {
        @Override public BooleanImage getBinaryImage(ImageProcessor ip) {
            boolean[] bits=new boolean[ip.getWidth()*ip.getHeight()];
            for(int i=0;i<bits.length;i++) bits[i]=ip.get(i)>127;
            return new BooleanImage(ip.getWidth(),ip.getHeight(),bits);
        }
        @Override public int getSupportedImageType() { return ImagePlus.GRAY8; }
        @Override public boolean isSupported(ImageProcessor ip) { return ip instanceof ByteProcessor; }
        @Override public boolean isSupported(ImagePlus ip) { return ip.getProcessor() instanceof ByteProcessor; }
        @Override public CustomisableProcess getNewInstance() { return new BinaryThreshold(); }
        @Override public String getName() { return "Honeydew binary foreground"; }
        @Override public void showGUI() { /* Parameters are owned by the application. */ }
    }
}
