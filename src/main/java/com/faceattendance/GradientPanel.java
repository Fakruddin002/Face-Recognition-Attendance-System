package com.faceattendance;

import javax.swing.*;
import java.awt.*;

public class GradientPanel extends JPanel {
    private Color startColor;
    private Color endColor;

    public GradientPanel(Color startColor, Color endColor) {
        this.startColor = startColor;
        this.endColor = endColor;
        setOpaque(false);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth();
        int h = getHeight();
        // Vertical gradient background
        GradientPaint gp = new GradientPaint(0, 0, startColor, 0, h, endColor);
        g2.setPaint(gp);
        g2.fillRect(0, 0, w, h);

        // Subtle decorative translucent circles to make the UI richer (like dashboard cards)
        // These overlays are very light and won't distract from content.
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.12f));
        Color circle = Color.WHITE;
        g2.setColor(circle);
        int max = Math.max(w, h);
        // top-right large circle
        int r1 = (int) (0.6f * max);
        g2.fillOval(w - r1 - 10, 10 - r1 / 3, r1, r1);
        // center-right medium
        int r2 = (int) (0.45f * max);
        g2.fillOval(w - r2 - 40, h / 3 - r2 / 2, r2, r2);
        // bottom-left soft circle
        int r3 = (int) (0.5f * max);
        g2.fillOval(-r3 / 3, h - r3, r3, r3);
        // restore
        g2.setComposite(AlphaComposite.SrcOver);

        g2.dispose();
    }
}
