package org.konagy.honeydew;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.awt.image.BufferedImage;
import java.awt.Graphics2D;
import java.awt.Color;
import ij.process.ByteProcessor;
import static org.junit.jupiter.api.Assertions.*;
import static org.konagy.honeydew.Project.*;

class PipelineTest {
    @TempDir Path temporary;
    @Test void actualAnimalTrackerFindsTwoComponents() {
        boolean[] mask=new boolean[40*30];
        for(int y=3;y<8;y++)for(int x=4;x<10;x++)mask[y*40+x]=true;
        for(int y=15;y<20;y++)for(int x=25;x<29;x++)mask[y*40+x]=true;
        var blobs=new Components().extract(mask,40,30);
        assertEquals(2,blobs.size());assertEquals(50,blobs.stream().mapToInt(b->b.getSize()).sum());
        assertTrue(blobs.stream().anyMatch(b->b.getBound().x==25 && b.getBound().y==15));
    }
    @Test void robustSimilarityRejectsOutliers() {
        List<Tracking.Pair> pairs=new ArrayList<>();
        Tracking.Transform expected=new Tracking.Transform(Math.cos(.08),Math.sin(.08),4,-3);
        for(int y=0;y<3;y++)for(int x=0;x<4;x++) {
            Point p=new Point(x*15,y*13);pairs.add(new Tracking.Pair(p,expected.apply(p)));
        }
        pairs.add(new Tracking.Pair(new Point(90,30),new Point(2,200)));
        var fit=Tracking.consensus(pairs,1.5);
        assertNotNull(fit);assertEquals(expected.a(),fit.a(),1e-6);assertEquals(expected.ty(),fit.ty(),1e-6);
    }
    @Test void persistenceSaveLoadKeepsReferencesSeparate() throws Exception {
        Path video=temporary.resolve("fixture.mp4");Files.write(video,new byte[]{1,2,3});
        Project p=Project.create(video);
        p.setAnchor(new Anchor(0,new Box(10,10,50,50),new Point(50,20),new Point(20,40)));
        p.candidates.add(new Event(1,1.1,1.05,20,40,17));
        p.candidates.getFirst().decision="Accepted";
        Path project=temporary.resolve("project.json");p.save(project);
        Project restored=Project.load(project);
        assertEquals("Accepted",restored.candidates.getFirst().decision);
        assertTrue(restored.references.isEmpty());
        Files.write(video,new byte[]{9,8,7,6});
        assertThrows(java.io.IOException.class,()->Project.load(project));
    }
    @Test void duplicateAndBadAnchorsAreRejected() throws Exception {
        Path video=temporary.resolve("v");Files.write(video,new byte[]{1});
        Project p=Project.create(video);
        Anchor a=new Anchor(0,new Box(1,1,40,40),new Point(30,20),new Point(10,20));
        p.anchors.add(a);p.anchors.add(a);
        assertThrows(java.io.IOException.class,p::validate);
    }
    private static ByteProcessor scene(boolean drop,boolean dark) {
        ByteProcessor image=new ByteProcessor(320,240);
        image.setValue(130);image.fill();
        if(drop) {image.setValue(dark?35:230);image.fillOval(103,115,9,9);}
        return image;
    }
    @Test void brightAndDarkTransientCandidatesAreDetectedAndFlushedOnce() {
        for(boolean dark:new boolean[]{false,true}) {
            List<Event> events=new ArrayList<>();Detector detector=new Detector(new Settings(),events);
            Tracking.Pose pose=new Tracking.Pose(new Point(220,120),new Point(120,120),1,true,"Test");
            for(int i=0;i<30;i++)detector.process(scene(i>=16 && i<=19,dark),pose,i/30.0);
            detector.flush();detector.flush();
            assertEquals(1,events.size(),"polarity="+dark);
            assertTrue(events.getFirst().start>=.5);
        }
    }
    @Test void staticBackgroundDoesNotProduceEvents() {
        List<Event> events=new ArrayList<>();Detector detector=new Detector(new Settings(),events);
        var pose=new Tracking.Pose(new Point(220,120),new Point(120,120),1,true,"Test");
        for(int i=0;i<90;i++)detector.process(scene(false,false),pose,i/30.0);
        detector.flush();assertTrue(events.isEmpty());
    }
    @Test void trackingLossCannotBeReportedAsAssessed() {
        Detector detector=new Detector(new Settings(),new ArrayList<>());
        var lost=new Tracking.Pose(new Point(220,120),new Point(120,120),0,false,"Lost");
        assertFalse(detector.process(scene(false,false),lost,1).assessed());
    }
    @Test void resetDoesNotJoinEventsAcrossManualCorrections() {
        List<Event> events=new ArrayList<>();Detector detector=new Detector(new Settings(),events);
        var pose=new Tracking.Pose(new Point(220,120),new Point(120,120),1,true,"Test");
        for(int i=0;i<20;i++)detector.process(scene(i>=16,false),pose,i/30.0);
        detector.reset(1);
        for(int i=0;i<20;i++)detector.process(scene(i>=16,false),pose,1+i/30.0);
        detector.flush();assertEquals(2,events.size());
    }
    @Test void opticalFlowTracksTranslationAndRefusesEmptyFrame() {
        BufferedImage first=new BufferedImage(320,240,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=first.createGraphics();g.setColor(Color.GRAY);g.fillRect(0,0,320,240);
        Random random=new Random(42);
        for(int i=0;i<150;i++){g.setColor(new Color(random.nextInt(256),random.nextInt(256),random.nextInt(256)));g.fillRect(125+random.nextInt(85),100+random.nextInt(40),4,4);}g.dispose();
        BufferedImage shifted=new BufferedImage(320,240,BufferedImage.TYPE_INT_RGB);
        Graphics2D h=shifted.createGraphics();h.setColor(Color.GRAY);h.fillRect(0,0,320,240);h.drawImage(first,3,2,null);h.dispose();
        try(Tracking tracker=new Tracking()){
            var firstPose=tracker.reset(Tracking.gray(first),new Anchor(0,new Box(110,90,120,60),new Point(220,120),new Point(120,120)),0);
            assertTrue(firstPose.valid());
            var moved=tracker.update(Tracking.gray(shifted),.033);
            assertTrue(moved.valid(),moved.reason());assertEquals(123,moved.tail().x(),1);assertEquals(122,moved.tail().y(),1);
            assertFalse(tracker.update(new ByteProcessor(320,240),.066).valid());
            assertFalse(tracker.update(Tracking.gray(shifted),.1).valid(),"Loss must remain explicit until reset.");
        }
    }
    @Test void intervalCoalescingPreservesReasons() {
        List<Interval> intervals=new ArrayList<>();
        Analysis.addInterval(intervals,0,1,"Warm-up");Analysis.addInterval(intervals,1,2,"Warm-up");Analysis.addInterval(intervals,2,3,"Lost");
        assertEquals(2,intervals.size());assertEquals(2,intervals.getFirst().end());
    }
    @Test void partlyClippedFarCornerStillAllowsVisibleTipAssessment() {
        List<Event> events=new ArrayList<>();Detector detector=new Detector(new Settings(),events);
        var pose=new Tracking.Pose(new Point(299,523),new Point(158,612),1,true,"Test");
        ByteProcessor frame=new ByteProcessor(1280,720);frame.setValue(130);frame.fill();
        Detector.Result result=null;
        for(int i=0;i<20;i++)result=detector.process(frame,pose,i/30.0);
        assertTrue(result.assessed());assertTrue(events.isEmpty());
    }
    @Test void distantMovingObjectCannotStartAnEvent() {
        List<Event> events=new ArrayList<>();Detector detector=new Detector(new Settings(),events);
        var pose=new Tracking.Pose(new Point(220,120),new Point(120,120),1,true,"Test");
        for(int i=0;i<40;i++){
            ByteProcessor frame=scene(false,false);
            if(i>15){frame.setValue(230);frame.fillOval(65-i%3,110,8,8);}
            detector.process(frame,pose,i/30.0);
        }
        detector.flush();assertTrue(events.isEmpty());
    }
    @Test void persistedChangeDoesNotCreateAnInfiniteGhost() {
        List<Event> events=new ArrayList<>();Detector detector=new Detector(new Settings(),events);
        var pose=new Tracking.Pose(new Point(220,120),new Point(120,120),1,true,"Test");
        for(int i=0;i<120;i++)detector.process(scene(i>=16,false),pose,i/30.0);
        detector.flush();
        assertFalse(events.isEmpty());
        assertTrue(events.stream().allMatch(e->e.end<2),"Stable new background should be absorbed.");
    }
    @Test void duplicateTimestampIsUnassessed() {
        Detector detector=new Detector(new Settings(),new ArrayList<>());
        var pose=new Tracking.Pose(new Point(220,120),new Point(120,120),1,true,"Test");
        detector.process(scene(false,false),pose,0);
        Detector.Result result=detector.process(scene(false,false),pose,0);
        assertFalse(result.assessed());assertEquals("Timestamp discontinuity",result.reason());
    }
    @Test void stationaryTextureIsNotAnEventWhenBodyCoordinatesMove() {
        List<Event> events=new ArrayList<>();Detector detector=new Detector(new Settings(),events);
        ByteProcessor frame=new ByteProcessor(320,240);
        Random random=new Random(7);byte[] pixels=(byte[])frame.getPixels();random.nextBytes(pixels);
        for(int i=0;i<60;i++){
            double shift=i<15?0:(i-15)*.2;
            var pose=new Tracking.Pose(new Point(220,120+shift),new Point(120,120+shift),1,true,"Test");
            detector.process(frame,pose,i/30.0);
        }
        detector.flush();assertTrue(events.isEmpty(),"Static scene texture must fail the scene-novelty check.");
    }
    @Test void incomingObjectIsNotMistakenForBirthAtTheAbdomenTip() {
        List<Event> events=new ArrayList<>();Detector detector=new Detector(new Settings(),events);
        var pose=new Tracking.Pose(new Point(220,120),new Point(120,120),1,true,"Test");
        for(int i=0;i<40;i++){
            ByteProcessor frame=scene(false,false);
            if(i>=16 && i<33){frame.setValue(230);frame.fillOval(65+(i-16)*3,115,8,8);}
            detector.process(frame,pose,i/30.0);
        }
        detector.flush();assertTrue(events.isEmpty());
    }
}
