package org.konagy.honeydew;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import ij.ImagePlus;
import ij.gui.*;
import ij.io.FileSaver;
import static org.konagy.honeydew.Project.*;
import org.konagy.honeydew.Project.Point;
import org.konagy.honeydew.Project.Box;
import org.konagy.honeydew.Project.Event;

/** All state changes happen on the EDT; decoding and analysis run in a single background worker. */
final class ReviewPanel extends JPanel {
    private Project project;
    private Path projectPath;
    private Video preview;
    private Video.Sample sample;
    private double duration;
    private boolean busy,dirty;
    private final AtomicBoolean canceled=new AtomicBoolean();
    private final List<JButton> controls=new ArrayList<>();
    private final Canvas canvas=new Canvas();
    private final JLabel status=new JLabel("Open a video to create a project. This is an experimental candidate detector.");
    private final JLabel time=new JLabel("No video");
    private final JProgressBar progress=new JProgressBar(0,1000);
    private final JSpinner seek=new JSpinner(new SpinnerNumberModel(0.0,0.0,1_000_000.0,.1));
    private final DefaultListModel<Event> eventModel=new DefaultListModel<>();
    private final JList<Event> events=new JList<>(eventModel);
    private final DefaultListModel<Interval> issueModel=new DefaultListModel<>();
    private final JList<Interval> issues=new JList<>(issueModel);
    private final JTextArea referenceText=new JTextArea();
    private final JButton cancel=new JButton("Cancel analysis");
    private final javax.swing.Timer playback=new javax.swing.Timer(40,e->{if(!busy && preview!=null) next();});
    private int markStage;
    private Point dragStart,head,tail;
    private Box box;

    ReviewPanel() {
        super(new BorderLayout(8,8));
        setBorder(BorderFactory.createEmptyBorder(10,10,10,10));
        JPanel toolbar=new JPanel(new FlowLayout(FlowLayout.LEFT));
        toolbar.add(button("Open video",this::openVideo));toolbar.add(button("Open project",this::openProject));
        toolbar.add(button("Save project",this::save));toolbar.add(button("Export CSV",this::exportCsv));
        toolbar.add(button("Open frame in Fiji",this::openFiji));
        add(toolbar,BorderLayout.NORTH);
        JPanel viewer=new JPanel(new BorderLayout(5,5));viewer.add(canvas,BorderLayout.CENTER);
        JPanel transport=new JPanel(new FlowLayout(FlowLayout.LEFT));
        transport.add(button("−0.1 s",()->go(sample==null?0:Math.max(0,sample.seconds()-.1))));
        transport.add(button("Next frame",this::next));
        transport.add(button("Play / pause",()->{if(playback.isRunning()) playback.stop();else if(preview!=null) playback.start();}));
        transport.add(seek);transport.add(button("Go to seconds",()->go(((Number)seek.getValue()).doubleValue())));transport.add(time);
        viewer.add(transport,BorderLayout.SOUTH);
        JPanel side=new JPanel(new BorderLayout(5,5));side.setPreferredSize(new Dimension(355,600));
        JPanel actions=new JPanel(new GridLayout(0,1,4,4));
        actions.add(new JLabel("<html><b>1. Mark → 2. Analyze → 3. Review</b><br>Draw body box, click head, then abdomen tip.<br>Repeat at a later frame to correct tracking.</html>"));
        actions.add(button("Mark / correct insect here",()->{
            if(sample==null) return;playback.stop();markStage=1;box=null;head=null;tail=null;
            status.setText("Drag a tight body rectangle. Avoid the second insect.");canvas.repaint();
        }));
        actions.add(button("Analyze from start",this::analyze));
        cancel.setEnabled(false);cancel.addActionListener(e->{canceled.set(true);cancel.setEnabled(false);status.setText("Stopping after the current frame…");});actions.add(cancel);
        actions.add(button("Add independent reference annotation",()->annotate(false)));
        actions.add(button("Mark fully reviewed interval",()->annotate(true)));
        side.add(actions,BorderLayout.NORTH);
        JTabbedPane tabs=new JTabbedPane();
        JPanel candidateTab=new JPanel(new BorderLayout());candidateTab.add(new JScrollPane(events),BorderLayout.CENTER);
        JPanel decisions=new JPanel(new GridLayout(2,2,4,4));
        decisions.add(button("Show candidate",()->{Event e=events.getSelectedValue();if(e!=null) go(e.peak);}));
        decisions.add(button("Accept",()->decide("Accepted")));
        decisions.add(button("Reject",()->decide("Rejected")));
        decisions.add(button("Uncertain",()->decide("Uncertain")));
        candidateTab.add(decisions,BorderLayout.SOUTH);
        tabs.addTab("Candidates",candidateTab);
        JPanel issueTab=new JPanel(new BorderLayout());issueTab.add(new JScrollPane(issues),BorderLayout.CENTER);
        issueTab.add(button("Show selected interval",()->{Interval i=issues.getSelectedValue();if(i!=null) go(i.start());}),BorderLayout.SOUTH);
        issues.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l,Object v,int i,boolean selected,boolean focus) {
                Interval interval=(Interval)v;
                return super.getListCellRendererComponent(l,String.format(Locale.ROOT,"%.2f–%.2f s: %s",interval.start(),interval.end(),interval.reason()),i,selected,focus);
            }
        });
        tabs.addTab("Unassessed",issueTab);
        referenceText.setEditable(false);referenceText.setLineWrap(true);referenceText.setWrapStyleWord(true);
        tabs.addTab("References",new JScrollPane(referenceText));
        side.add(tabs,BorderLayout.CENTER);
        JSplitPane split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,viewer,side);split.setResizeWeight(.72);add(split,BorderLayout.CENTER);
        JPanel footer=new JPanel(new BorderLayout(4,4));footer.add(status,BorderLayout.CENTER);footer.add(progress,BorderLayout.SOUTH);add(footer,BorderLayout.SOUTH);
    }
    private JButton button(String label,Runnable action) {
        JButton b=new JButton(label);b.addActionListener(e->{try {action.run();}catch(Exception ex){error(ex);}});
        controls.add(b);return b;
    }
    private void setBusy(boolean value) {
        busy=value;for(JButton b:controls)b.setEnabled(!value);seek.setEnabled(!value);
    }
    private <T> void work(Callable<T> task,Consumer<T> success) {
        if(busy)return;setBusy(true);
        new SwingWorker<T,Void>() {
            @Override protected T doInBackground() throws Exception {return task.call();}
            @Override protected void done() {
                setBusy(false);cancel.setEnabled(false);
                try {success.accept(get());}catch(Exception e){playback.stop();error(e.getCause()==null?e:e.getCause());}
            }
        }.execute();
    }
    private void error(Throwable e) {status.setText("Error: "+e.getMessage());JOptionPane.showMessageDialog(this,e.getMessage(),"Operation failed",JOptionPane.ERROR_MESSAGE);}
    private Path choose(boolean save,String title) {
        JFileChooser picker=new JFileChooser(projectPath==null?Path.of(".").toFile():projectPath.toFile().getParentFile());
        picker.setDialogTitle(title);
        if((save?picker.showSaveDialog(this):picker.showOpenDialog(this))!=JFileChooser.APPROVE_OPTION)return null;
        Path p=picker.getSelectedFile().toPath().toAbsolutePath();
        if(save && Files.exists(p) && JOptionPane.showConfirmDialog(this,"Replace "+p.getFileName()+"?","Confirm overwrite",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return null;
        return p;
    }
    private boolean discard() {playback.stop();return !dirty || JOptionPane.showConfirmDialog(this,"Discard unsaved project changes?","Unsaved changes",JOptionPane.YES_NO_OPTION)==JOptionPane.YES_OPTION;}
    private void openVideo() {
        if(!discard())return;Path path=choose(false,"Open source video");if(path==null)return;
        work(()->Project.create(path),p->loadPreview(p,null));
    }
    private void openProject() {
        if(!discard())return;Path path=choose(false,"Open Honeydew project JSON");if(path==null)return;
        work(()->Project.load(path),p->loadPreview(p,path));
    }
    private void loadPreview(Project p,Path path) {
        work(()->{
            Video video=new Video(Path.of(p.video));
            try {Video.Sample first=video.next();if(first==null)throw new IllegalArgumentException("Video contains no decoded images.");
                return new Object[]{video,first,video.duration()};
            }catch(Exception e){video.close();throw e;}
        },result->{
            closePreview();preview=(Video)result[0];sample=(Video.Sample)result[1];duration=(double)result[2];
            project=p;projectPath=path;dirty=path==null;markStage=0;box=null;head=null;tail=null;
            refresh();show(sample);status.setText("Source: "+Path.of(project.video).getFileName()+" | "+project.runStatus);
        });
    }
    private void show(Video.Sample s) {
        if(s==null){playback.stop();status.setText("End of video.");return;}
        sample=s;seek.setValue(s.seconds());time.setText(String.format(Locale.ROOT,"PTS %.3f / %.2f s",s.seconds(),duration));canvas.repaint();
    }
    private void go(double seconds) {if(preview==null)return;playback.stop();markStage=0;work(()->preview.seek(Math.max(0,Math.min(seconds,duration))),this::show);}
    private void next() {if(preview==null)return;markStage=0;work(()->preview.next(),this::show);}
    private void save() {
        if(project==null)return;playback.stop();
        Path p=projectPath==null?choose(true,"Save project as JSON"):projectPath;if(p==null)return;
        Project snapshot=project.copy();
        work(()->{snapshot.save(p);return p;},saved->{projectPath=saved;dirty=false;status.setText("Saved "+saved);});
    }
    private void exportCsv() {
        if(project==null)return;Path p=choose(true,"Export candidate CSV");if(p==null)return;
        work(()->{project.exportCsv(p);return p;},saved->status.setText("Exported "+saved+" — candidate decisions are not ground truth."));
    }
    private void analyze() {
        if(project==null)return;playback.stop();
        if(project.anchors.isEmpty()){status.setText("Mark the insect before analysis.");return;}
        if(!project.candidates.isEmpty() && JOptionPane.showConfirmDialog(this,"A new run replaces existing candidates and their review decisions. Save a copy first if needed.","New analysis",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
        canceled.set(false);
        Project input=project.copy();
        work(()->Analysis.run(input,canceled::get,p->SwingUtilities.invokeLater(()->{
            progress.setValue(p.duration()>0?(int)(1000*p.seconds()/p.duration()):0);
            status.setText(String.format(Locale.ROOT,"%.1f / %.1f s | %d candidates | %s",p.seconds(),p.duration(),p.candidates(),p.tracking()));
        })),result->{project=result;dirty=true;refresh();status.setText(result.runStatus+" | "+result.candidates.size()+" candidates | "+result.unassessed.size()+" unassessed intervals");});
        cancel.setEnabled(true);
    }
    private void decide(String decision) {Event event=events.getSelectedValue();if(event!=null){event.decision=decision;dirty=true;events.repaint();}}
    private void annotate(boolean reviewed) {
        if(project==null || sample==null)return;playback.stop();
        JTextField start=new JTextField(String.format(Locale.ROOT,"%.6f",sample.seconds()));
        JTextField end=new JTextField(String.format(Locale.ROOT,"%.6f",sample.seconds()));
        JComboBox<String> label=new JComboBox<>(new String[]{"Confirmed excretion","Uncertain","Other insect / artifact"});
        JTextField notes=new JTextField();
        JPanel panel=new JPanel(new GridLayout(0,2,4,4));panel.add(new JLabel("Start seconds"));panel.add(start);panel.add(new JLabel("End seconds"));panel.add(end);
        if(!reviewed){panel.add(new JLabel("Label"));panel.add(label);panel.add(new JLabel("Notes"));panel.add(notes);}
        else {panel.add(new JLabel("<html>Confirm you reviewed the entire interval,<br>including places with no candidates.</html>"));panel.add(new JLabel(""));}
        if(JOptionPane.showConfirmDialog(this,panel,reviewed?"Full-interval review":"Independent reference annotation",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
        double a=Double.parseDouble(start.getText()),b=Double.parseDouble(end.getText());
        if(!Double.isFinite(a)||!Double.isFinite(b)||a<0||b<a||b>duration+.001)throw new IllegalArgumentException("Invalid interval.");
        if(reviewed) project.reviewed.add(new Interval(a,b,"Manually reviewed in full"));
        else project.references.add(new Reference(a,b,(String)label.getSelectedItem(),notes.getText()));
        dirty=true;refresh();
    }
    private void refresh() {
        eventModel.clear();project.candidates.forEach(eventModel::addElement);
        issueModel.clear();project.unassessed.forEach(issueModel::addElement);
        StringBuilder s=new StringBuilder("Independent references (not inferred from candidate decisions)\n\n");
        for(Reference r:project.references)s.append(String.format(Locale.ROOT,"%.3f–%.3f s: %s — %s%n",r.start(),r.end(),r.label(),r.notes()));
        s.append("\nFully reviewed intervals:\n");
        for(Interval i:project.reviewed)s.append(String.format(Locale.ROOT,"%.3f–%.3f s%n",i.start(),i.end()));
        s.append("\nTracking anchors:\n");
        for(Anchor a:project.anchors)s.append(String.format(Locale.ROOT,"%.6f s%n",a.seconds()));
        referenceText.setText(s.toString());canvas.repaint();
    }
    private void openFiji() {
        if(sample==null)return;playback.stop();
        Path launcher=Path.of(".tools","fiji","Fiji","fiji-windows-x64.exe").toAbsolutePath();
        if(!Files.isRegularFile(launcher))throw new IllegalStateException("Fiji is not installed. Run v2/setup.ps1 -IncludeFiji.");
        BufferedImage image=sample.image();double pts=sample.seconds();
        work(()->{
            Path folder=Path.of("artifacts","fiji").toAbsolutePath();Files.createDirectories(folder);
            Path imagePath=Files.createTempFile(folder,"frame-", ".tif");
            ImagePlus imp=new ImagePlus(String.format(Locale.ROOT,"Frame at %.6f s",pts),image);
            Overlay overlay=new Overlay();
            for(Anchor a:project.anchors)if(Math.abs(a.seconds()-pts)<.001){
                Roi body=new Roi(a.body().x(),a.body().y(),a.body().width(),a.body().height());body.setStrokeColor(Color.GREEN);overlay.add(body);
                Line axis=new Line(a.head().x(),a.head().y(),a.tail().x(),a.tail().y());axis.setStrokeColor(Color.CYAN);overlay.add(axis);
            }
            imp.setOverlay(overlay);
            if(!new FileSaver(imp).saveAsTiff(imagePath.toString()))throw new IllegalStateException("Could not export Fiji frame.");
            new ProcessBuilder(launcher.toString(),imagePath.toString()).directory(launcher.getParent().toFile())
                .redirectErrorStream(true).redirectOutput(imagePath.resolveSibling(imagePath.getFileName()+".log").toFile()).start();
            return imagePath;
        },p->status.setText("Opened exported frame in Fiji. Edits in Fiji do not change the project automatically."));
    }
    boolean canClose() {
        if(busy){canceled.set(true);status.setText("Wait for the current operation to finish before closing.");return false;}
        return discard();
    }
    void close() {playback.stop();closePreview();}
    private void closePreview() {if(preview!=null){try{preview.close();}catch(Exception ignored){}preview=null;}}
    private final class Canvas extends JPanel {
        private double scale=1;private int ox,oy;
        Canvas() {
            setBackground(new Color(24,28,33));setPreferredSize(new Dimension(850,650));
            MouseAdapter mouse=new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {if(!busy && markStage==1)dragStart=point(e);}
                @Override public void mouseReleased(MouseEvent e) {
                    if(busy || sample==null)return;
                    Point p=point(e);if(p==null)return;
                    if(markStage==1 && dragStart!=null) {
                        box=new Box(Math.min(dragStart.x(),p.x()),Math.min(dragStart.y(),p.y()),Math.abs(p.x()-dragStart.x()),Math.abs(p.y()-dragStart.y()));
                        if(box.width()<12||box.height()<12){status.setText("Body box is too small. Drag again.");return;}
                        markStage=2;status.setText("Click the head.");
                    } else if(markStage==2) {head=p;markStage=3;status.setText("Click the abdomen tip (where a droplet emerges).");}
                    else if(markStage==3) {
                        tail=p;if(head.distance(tail)<10){status.setText("Head and abdomen tip must be at least 10 pixels apart.");return;}
                        project.setAnchor(new Anchor(sample.seconds(),box,head,tail));dirty=true;markStage=0;refresh();
                        status.setText("Anchor saved at "+String.format(Locale.ROOT,"%.6f s. Ready to analyze.",sample.seconds()));
                    }
                    repaint();
                }
            };
            addMouseListener(mouse);
        }
        private Point point(MouseEvent e) {
            if(sample==null)return null;
            double x=(e.getX()-ox)/scale,y=(e.getY()-oy)/scale;
            return x>=0&&y>=0&&x<sample.image().getWidth()&&y<sample.image().getHeight()?new Point(x,y):null;
        }
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);Graphics2D g=(Graphics2D)graphics.create();
            g.setColor(Color.LIGHT_GRAY);
            if(sample==null){g.drawString("Open a video. Mark the insect at its original resolution.",25,40);g.dispose();return;}
            BufferedImage image=sample.image();
            scale=Math.min((double)getWidth()/image.getWidth(),(double)(getHeight()-120)/image.getHeight());
            ox=(int)((getWidth()-image.getWidth()*scale)/2);oy=5;
            g.translate(ox,oy);g.scale(scale,scale);g.drawImage(image,0,0,null);
            g.setStroke(new BasicStroke((float)(2/scale)));
            if(project!=null)for(Anchor a:project.anchors)if(Math.abs(a.seconds()-sample.seconds())<.001)drawAnchor(g,a.body(),a.head(),a.tail());
            if(markStage>0)drawAnchor(g,box,head,tail);
            if(project!=null)for(Event event:project.candidates)if(Math.abs(event.peak-sample.seconds())<.05){g.setColor(Color.ORANGE);g.draw(new Ellipse2D.Double(event.x-12,event.y-12,24,24));}
            g.dispose();
            Graphics2D detail=(Graphics2D)graphics.create();detail.setColor(Color.WHITE);
            detail.drawString("Manual anchors appear only on their exact frame. Orange: candidate peak. No confidence probability is implied.",10,getHeight()-100);
            Point focus=null;
            Event event=events.getSelectedValue();
            if(event!=null && Math.abs(event.peak-sample.seconds())<.2)focus=new Point(event.x,event.y);
            else if(project!=null)for(Anchor a:project.anchors)if(Math.abs(a.seconds()-sample.seconds())<.001)focus=a.tail();
            if(focus!=null) {
                int x=(int)focus.x(),y=(int)focus.y();
                detail.drawImage(image,10,getHeight()-90,170,getHeight()-10,Math.max(0,x-40),Math.max(0,y-20),Math.min(image.getWidth(),x+40),Math.min(image.getHeight(),y+20),null);
                detail.drawString("2× detail — source pixels retained",180,getHeight()-45);
            }
            detail.dispose();
        }
        private void drawAnchor(Graphics2D g,Box b,Point h,Point t) {
            g.setColor(Color.GREEN);if(b!=null)g.draw(new Rectangle2D.Double(b.x(),b.y(),b.width(),b.height()));
            if(h!=null){g.setColor(Color.CYAN);g.fill(new Ellipse2D.Double(h.x()-3,h.y()-3,6,6));}
            if(t!=null){g.setColor(Color.MAGENTA);g.fill(new Ellipse2D.Double(t.x()-3,t.y()-3,6,6));if(h!=null)g.draw(new Line2D.Double(h.x(),h.y(),t.x(),t.y()));}
        }
    }
    static void render(Path projectFile,Path output) throws Exception {
        Project p=Project.load(projectFile);Video.Sample s;double length;
        try(Video v=new Video(Path.of(p.video))){s=p.candidates.isEmpty()?v.next():v.seek(p.candidates.getFirst().peak);length=v.duration();}
        SwingUtilities.invokeAndWait(()->{
            ReviewPanel panel=new ReviewPanel();panel.project=p;panel.sample=s;panel.duration=length;panel.refresh();panel.show(s);
            if(!p.candidates.isEmpty())panel.events.setSelectedIndex(0);
            panel.status.setText(p.runStatus+" | "+p.candidates.size()+" candidates | "+p.unassessed.size()+" unassessed intervals");
            panel.setSize(1280,900);layout(panel);
            BufferedImage image=new BufferedImage(1280,900,BufferedImage.TYPE_INT_RGB);Graphics2D graphics=image.createGraphics();panel.printAll(graphics);graphics.dispose();
            try{ImageIO.write(image,"png",output.toFile());}catch(Exception e){throw new RuntimeException(e);}
        });
    }
    private static void layout(Container c) {c.doLayout();for(Component child:c.getComponents())if(child instanceof Container nested)layout(nested);}
}
