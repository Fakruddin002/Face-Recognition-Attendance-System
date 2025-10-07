package com.faceattendance;

import javax.swing.*;
import javax.swing.plaf.basic.BasicButtonUI;
import java.awt.*;

public class GradientButtonUI extends BasicButtonUI {

    private Color startColor;
    private Color endColor;

    public GradientButtonUI(Color startColor, Color endColor) {
        this.startColor = startColor;
        this.endColor = endColor;
    }

    @Override
    public void paint(Graphics g, JComponent c) {
        AbstractButton b = (AbstractButton) c;
        Graphics2D g2 = (Graphics2D) g.create();

        // Draw gradient
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = b.getWidth();
        int h = b.getHeight();
        GradientPaint gp = new GradientPaint(0, 0, startColor, 0, h, endColor);
        g2.setPaint(gp);
        g2.fillRoundRect(0, 0, w, h, 20, 20);

        // Draw button text
        g2.setColor(b.getForeground());
        FontMetrics fm = g2.getFontMetrics();
        String text = b.getText();
        int tx = (w - fm.stringWidth(text)) / 2;
        int ty = (h + fm.getAscent() - fm.getDescent()) / 2;
        g2.drawString(text, tx, ty);

        g2.dispose();
    }
}
