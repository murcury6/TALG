package io.github.murcury6.talg;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.JViewport;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicScrollBarUI;

/** A free-positioned, scrollable desktop rather than a grid of reserved slots. */
final class ModularWorkspace extends JPanel {
    private static final Color BG = new Color(20, 18, 27);
    private static final Color PANEL = new Color(34, 30, 45);
    private static final Color BORDER = new Color(70, 62, 87);
    private static final Color TEXT = new Color(232, 229, 239);
    private static final Color PURPLE = new Color(177, 146, 245);
    private static final Dimension CANVAS_SIZE = new Dimension(3600, 2400);
    private static final Point HOME = new Point(1100, 700);

    private final Supplier<ConfigurablePanel> createPanel;
    private final List<Widget> widgets = new ArrayList<>();
    private final Canvas canvas = new Canvas();
    private final JScrollPane scroll = new JScrollPane(canvas);

    ModularWorkspace(Supplier<ConfigurablePanel> createPanel) {
        super(new BorderLayout());
        this.createPanel = createPanel;
        setBackground(BG);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(BG);
        scroll.getVerticalScrollBar().setUnitIncrement(28);
        scroll.getHorizontalScrollBar().setUnitIncrement(28);
        styleScrollBar(scroll.getVerticalScrollBar());
        styleScrollBar(scroll.getHorizontalScrollBar());
        add(scroll, BorderLayout.CENTER);
        SwingUtilities.invokeLater(() -> scroll.getViewport().setViewPosition(HOME));
    }

    void centerView() {
        scroll.getViewport().setViewPosition(HOME);
    }

    ConfigurablePanel addPanel() {
        ConfigurablePanel panel = createPanel.get();
        Widget widget = new Widget(panel);
        Dimension preferred = new Dimension(720, 490);
        Rectangle view = visibleArea();
        int width = Math.min(preferred.width, Math.max(360, view.width - 60));
        int height = Math.min(preferred.height, Math.max(260, view.height - 70));
        int offset = widgets.size() * 26;
        int x = Math.min(CANVAS_SIZE.width - width, Math.max(0, view.x + (view.width - width) / 2 + offset));
        int y = Math.min(CANVAS_SIZE.height - height, Math.max(0, view.y + (view.height - height) / 2 + offset));
        widget.setBounds(x, y, width, height);
        widgets.add(widget);
        canvas.add(widget, JLayeredPane.DEFAULT_LAYER);
        canvas.moveToFront(widget);
        canvas.repaint();
        return panel;
    }

    int panelCount() {
        return widgets.size();
    }

    Point viewPosition() { return scroll.getViewport().getViewPosition(); }

    void setViewPosition(Point position) {
        SwingUtilities.invokeLater(() -> scroll.getViewport().setViewPosition(position));
    }

    List<PanelState> snapshot() {
        return widgets.stream().map(widget -> new PanelState(widget.getBounds(), widget.content.snapshot()))
                .toList();
    }

    void restore(List<PanelState> panels, Point viewPosition) {
        for (Widget widget : List.copyOf(widgets)) remove(widget);
        for (PanelState state : panels) {
            ConfigurablePanel panel = createPanel.get();
            Widget widget = new Widget(panel);
            widget.setBounds(state.bounds());
            widgets.add(widget);
            canvas.add(widget, JLayeredPane.DEFAULT_LAYER);
            panel.restore(state.panel());
        }
        setViewPosition(viewPosition);
        canvas.revalidate();
        canvas.repaint();
    }

    record PanelState(Rectangle bounds, ConfigurablePanel.State panel) {}

    private Rectangle visibleArea() {
        Rectangle view = scroll.getViewport().getViewRect();
        return view.width > 0 && view.height > 0 ? view : new Rectangle(HOME.x, HOME.y, 1300, 780);
    }

    private void remove(Widget widget) {
        widgets.remove(widget);
        canvas.remove(widget);
        widget.content.disposePanel();
        canvas.repaint();
    }

    private void fitToView(Widget widget) {
        if (widget.savedBounds == null) {
            widget.savedBounds = widget.getBounds();
            Rectangle view = visibleArea();
            widget.setBounds(view.x + 12, view.y + 12,
                    Math.max(360, view.width - 24), Math.max(260, view.height - 24));
        } else {
            widget.setBounds(widget.savedBounds);
            widget.savedBounds = null;
        }
        canvas.moveToFront(widget);
        canvas.repaint();
    }

    private static JButton button(String title) {
        JButton button = new JButton(title);
        button.setFont(new Font("Segoe UI", Font.BOLD, 10));
        button.setForeground(PURPLE);
        button.setBackground(PANEL);
        button.setBorder(new EmptyBorder(2, 6, 2, 6));
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    private static JLabel label(String value, int size, Color color, int weight) {
        JLabel label = new JLabel(value);
        label.setFont(new Font("Segoe UI", weight, size));
        label.setForeground(color);
        return label;
    }

    private static void styleScrollBar(javax.swing.JScrollBar bar) {
        bar.setPreferredSize(new Dimension(9, 9));
        bar.setUI(new BasicScrollBarUI() {
            @Override protected void configureScrollBarColors() {
                thumbColor = BORDER;
                trackColor = BG;
            }

            @Override protected JButton createDecreaseButton(int orientation) { return zeroButton(); }
            @Override protected JButton createIncreaseButton(int orientation) { return zeroButton(); }

            private JButton zeroButton() {
                JButton button = new JButton();
                button.setPreferredSize(new Dimension(0, 0));
                button.setMinimumSize(new Dimension(0, 0));
                button.setMaximumSize(new Dimension(0, 0));
                return button;
            }
        });
    }

    private final class Canvas extends JLayeredPane {
        private Point panStart;
        private Point viewStart;

        private Canvas() {
            setOpaque(true);
            setBackground(BG);
            setLayout(null);
            setPreferredSize(CANVAS_SIZE);
            MouseAdapter pan = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent event) {
                    if (!SwingUtilities.isMiddleMouseButton(event)) return;
                    panStart = event.getLocationOnScreen();
                    viewStart = scroll.getViewport().getViewPosition();
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                }

                @Override public void mouseDragged(MouseEvent event) {
                    if (panStart == null) return;
                    JViewport viewport = scroll.getViewport();
                    int maxX = Math.max(0, getWidth() - viewport.getWidth());
                    int maxY = Math.max(0, getHeight() - viewport.getHeight());
                    Point now = event.getLocationOnScreen();
                    int x = Math.max(0, Math.min(maxX, viewStart.x + panStart.x - now.x));
                    int y = Math.max(0, Math.min(maxY, viewStart.y + panStart.y - now.y));
                    viewport.setViewPosition(new Point(x, y));
                }

                @Override public void mouseReleased(MouseEvent event) {
                    panStart = null;
                    setCursor(Cursor.getDefaultCursor());
                }
            };
            addMouseListener(pan);
            addMouseMotionListener(pan);
        }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            Rectangle clip = g.getClipBounds();
            g.setColor(new Color(42, 37, 53));
            for (int x = Math.floorDiv(clip.x, 48) * 48; x < clip.x + clip.width; x += 48) {
                for (int y = Math.floorDiv(clip.y, 48) * 48; y < clip.y + clip.height; y += 48) {
                    g.fillOval(x, y, 2, 2);
                }
            }
            g.dispose();
        }
    }

    private final class Widget extends JPanel {
        private static final int MIN_WIDTH = 280;
        private static final int MIN_HEIGHT = 200;
        private final ConfigurablePanel content;
        private Rectangle savedBounds;

        private Widget(ConfigurablePanel content) {
            super(new BorderLayout());
            this.content = content;
            setBackground(BG);
            setBorder(BorderFactory.createLineBorder(BORDER));
            JPanel header = new JPanel(new BorderLayout());
            header.setBackground(PANEL);
            header.setPreferredSize(new Dimension(1, 18));
            header.setBorder(new EmptyBorder(0, 3, 0, 2));
            JLabel dragHandle = label("::", 11, PURPLE, Font.BOLD);
            dragHandle.setToolTipText("Drag to move this panel");
            header.add(dragHandle, BorderLayout.WEST);
            JLabel description = label(content.shortDescription(), 11, TEXT, Font.PLAIN);
            description.setBorder(new EmptyBorder(0, 4, 0, 0));
            description.setToolTipText(content.shortDescription() + " · drag to move");
            header.add(description, BorderLayout.CENTER);
            content.onDescriptionChanged(() -> {
                description.setText(content.shortDescription());
                description.setToolTipText(content.shortDescription() + " · drag to move");
            });
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
            actions.setOpaque(false);
            JButton quickButton = button("VAR");
            quickButton.setToolTipText("Show or hide quick chart variables on the left");
            quickButton.addActionListener(event -> content.toggleQuickVariables());
            actions.add(quickButton);
            JButton codeButton = button("{}");
            codeButton.setToolTipText("Open this panel's script and full configuration");
            codeButton.addActionListener(event -> content.showConfigurationEditor());
            actions.add(codeButton);
            JButton menuButton = button("...");
            menuButton.setToolTipText("Refresh and panel size");
            JPopupMenu menu = new JPopupMenu();
            JMenuItem refresh = new JMenuItem("Refresh now");
            refresh.addActionListener(event -> content.refreshVisible());
            menu.add(refresh);
            JMenuItem fit = new JMenuItem("Fit / restore size");
            fit.addActionListener(event -> fitToView(this));
            menu.add(fit);
            menuButton.addActionListener(event -> menu.show(menuButton, 0, menuButton.getHeight()));
            actions.add(menuButton);
            JButton close = button("X");
            close.setToolTipText("Remove this panel");
            close.addActionListener(event -> ModularWorkspace.this.remove(this));
            actions.add(close);
            header.add(actions, BorderLayout.EAST);
            add(header, BorderLayout.NORTH);
            ResizeGrip grip = new ResizeGrip();
            JLayeredPane body = new JLayeredPane() {
                @Override public void doLayout() {
                    content.setBounds(0, 0, getWidth(), getHeight());
                    grip.setBounds(Math.max(0, getWidth() - 14), Math.max(0, getHeight() - 14), 14, 14);
                }
            };
            body.add(content, JLayeredPane.DEFAULT_LAYER);
            body.add(grip, JLayeredPane.PALETTE_LAYER);
            add(body, BorderLayout.CENTER);

            MouseAdapter drag = new MouseAdapter() {
                private Point start;
                private Rectangle original;

                @Override public void mousePressed(MouseEvent event) {
                    if (!SwingUtilities.isLeftMouseButton(event)) return;
                    start = SwingUtilities.convertPoint(event.getComponent(), event.getPoint(), canvas);
                    original = getBounds();
                    canvas.moveToFront(Widget.this);
                }

                @Override public void mouseDragged(MouseEvent event) {
                    if (start == null) return;
                    Point now = SwingUtilities.convertPoint(event.getComponent(), event.getPoint(), canvas);
                    int x = Math.max(0, Math.min(CANVAS_SIZE.width - getWidth(), original.x + now.x - start.x));
                    int y = Math.max(0, Math.min(CANVAS_SIZE.height - getHeight(), original.y + now.y - start.y));
                    setLocation(x, y);
                    canvas.repaint();
                }

                @Override public void mouseReleased(MouseEvent event) { start = null; }
            };
            header.addMouseListener(drag);
            header.addMouseMotionListener(drag);
            dragHandle.addMouseListener(drag);
            dragHandle.addMouseMotionListener(drag);
            description.addMouseListener(drag);
            description.addMouseMotionListener(drag);
            header.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
            dragHandle.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
            description.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
        }

        private final class ResizeGrip extends JComponent {
            private Point start;
            private Rectangle original;

            private ResizeGrip() {
                setPreferredSize(new Dimension(14, 14));
                setCursor(Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR));
                addMouseListener(new MouseAdapter() {
                    @Override public void mousePressed(MouseEvent event) {
                        start = SwingUtilities.convertPoint(ResizeGrip.this, event.getPoint(), canvas);
                        original = Widget.this.getBounds();
                        canvas.moveToFront(Widget.this);
                    }

                    @Override public void mouseReleased(MouseEvent event) { start = null; }
                });
                addMouseMotionListener(new MouseAdapter() {
                    @Override public void mouseDragged(MouseEvent event) {
                        if (start == null) return;
                        Point now = SwingUtilities.convertPoint(ResizeGrip.this, event.getPoint(), canvas);
                        int width = Math.max(MIN_WIDTH, Math.min(CANVAS_SIZE.width - original.x,
                                original.width + now.x - start.x));
                        int height = Math.max(MIN_HEIGHT, Math.min(CANVAS_SIZE.height - original.y,
                                original.height + now.y - start.y));
                        Widget.this.setSize(width, height);
                        Widget.this.revalidate();
                        canvas.repaint();
                    }
                });
            }

            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setColor(PANEL);
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(PURPLE);
                g.setStroke(new BasicStroke(1.5f));
                for (int offset = 0; offset < 3; offset++) {
                    int edge = getWidth() - 7 - offset * 5;
                    g.drawLine(edge, getHeight() - 4, getWidth() - 4, getHeight() - 7 - offset * 5);
                }
                g.dispose();
            }
        }
    }
}
