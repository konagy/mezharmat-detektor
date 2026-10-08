package org.konagy.honeydew;

import javax.swing.*;
import java.nio.file.*;
import java.util.Locale;
import org.bytedeco.ffmpeg.global.avutil;

/** Desktop entry point and reproducible, non-interactive analysis commands. */
public final class App {
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ENGLISH);
        avutil.av_log_set_level(avutil.AV_LOG_ERROR);
        if(args.length>0) {
            try {
            switch(args[0]) {
                case "--analyze" -> {
                    if(args.length!=3) throw new IllegalArgumentException("--analyze input.json output.json");
                    Path output=Path.of(args[2]);
                    Path csv=output.resolveSibling(output.getFileName()+".csv");
                    if(Files.exists(output) || Files.exists(csv)) throw new IllegalArgumentException("Output or CSV already exists; choose a new path.");
                    Project result=Analysis.run(Project.load(Path.of(args[1])),()->false,
                        p->System.out.printf(Locale.ROOT,"%.2f / %.2f s | %d frames | %d candidates | %s%n",p.seconds(),p.duration(),p.frames(),p.candidates(),p.tracking()));
                    result.save(output);
                    result.exportCsv(csv);
                    System.out.printf(Locale.ROOT,"Finished: %d candidates, %d unassessed intervals, %.1f s wall time%n",
                        result.candidates.size(),result.unassessed.size(),result.wallSeconds);
                }
                case "--inspect" -> {
                    try(Video v=new Video(Path.of(args[1]))) {
                        Video.Sample first=v.next();
                        System.out.printf(Locale.ROOT,"duration=%.6f nominal_fps=%.6f first_pts=%.6f size=%dx%d%n",
                            v.duration(),v.fps(),first.seconds(),first.image().getWidth(),first.image().getHeight());
                    }
                }
                case "--render" -> ReviewPanel.render(Path.of(args[1]),Path.of(args[2]));
                default -> throw new IllegalArgumentException("Use --inspect VIDEO, --analyze INPUT.json OUTPUT.json or --render PROJECT.json OUTPUT.png");
            }
            } finally {shutdownImageJ();}
            return;
        }
        SwingUtilities.invokeLater(()->{
            try {UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());}
            catch(Exception ignored) {}
            JFrame frame=new JFrame("Honeydew Review — experimental rule-based detector");
            ReviewPanel panel=new ReviewPanel();frame.setContentPane(panel);
            frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            frame.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override public void windowClosing(java.awt.event.WindowEvent e) {
                    if(panel.canClose()) {panel.close();frame.dispose();shutdownImageJ();}
                }
            });
            frame.setSize(1280,900);frame.setMinimumSize(new java.awt.Dimension(1050,740));
            frame.setLocationRelativeTo(null);frame.setVisible(true);
        });
    }
    static void shutdownImageJ() {ij.util.ThreadUtil.threadPoolExecutor.shutdown();}
}
