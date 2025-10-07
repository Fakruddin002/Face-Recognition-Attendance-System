package com.faceattendance;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.awt.event.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import javax.swing.Timer;
import java.awt.Desktop;
import java.net.URI;

import com.formdev.flatlaf.FlatLightLaf;

import org.bytedeco.javacv.OpenCVFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.javacv.Java2DFrameConverter;

import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.RectVector;
import org.bytedeco.opencv.opencv_core.Size;
import org.bytedeco.opencv.opencv_face.LBPHFaceRecognizer;
import org.bytedeco.opencv.opencv_core.MatVector;

import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.DoublePointer;

import static org.bytedeco.opencv.global.opencv_imgproc.*;
import static org.bytedeco.opencv.global.opencv_imgcodecs.*;
import org.bytedeco.opencv.opencv_objdetect.CascadeClassifier;
import org.bytedeco.javacpp.Loader;

public class FaceAttendanceApp extends JFrame {
    // UI components
    private JButton dashboardBtn, studentsBtn, attendanceBtn, adminBtn;
    private CardLayout contentCards;
    private JPanel contentPanel;
    private JTable studentsTable;
    private JLabel cameraLabel;
    private JTextArea logArea;
    private JLabel statusBar;
    private JProgressBar enrollBar;
    private JDialog trainingDialog;
    private BufferedImage lastCameraFrame; // cache for responsive resizing

    // Dashboard statistics labels
    private JLabel totalStudentsLabel;
    private JLabel todaysAttendanceLabel;
    private JLabel totalRecordsLabel;
    private JLabel dayLabel;
    private JLabel dateLabel;
    private JLabel timeLabel;
    private Timer timeTimer;

    // Theming - Professional Color Palette
    private final Color PRIMARY = Color.decode("#2C3E50");       // Dark blue - headers and important elements
    private final Color SECONDARY = Color.decode("#3498DB");     // Light blue - buttons and highlights       
    private final Color BG_COLOR = Color.decode("#ECF0F1");      // Light gray - main background
    private final Color PANEL_COLOR = Color.WHITE;               // White for panels    // Dark text
    private final Color TEXT_LIGHT = Color.WHITE;                // Light text

    // DB
    private Connection conn;

    // Face / camera helpers
    private CascadeClassifier faceCascade;
    private volatile boolean recognitionRunning = false;
    private volatile boolean stopAfterFirstRecognize = false; // auto close mode for Mark Attendance
    private volatile boolean checkoutMode = false; // if true, mark check-out instead of check-in
    private OpenCVFrameGrabber grabber;

    // Separate recognition window controls
    private JDialog recognitionDialog;
    private JLabel recognitionLabel; // video label used inside dialog
    private volatile boolean usingRecognitionDialog = false;
    private volatile boolean waitingToClose = false;
    private volatile long closeAtMillis = 0L;

    // Paths & settings
    private final File datasetDir = new File("dataset");
    private final File modelsDir = new File("models");
    private final String cascadeFile = "haarcascade_frontalface_alt.xml"; // put in project root
    private final String modelFile = "models/lbph_model.xml";
    private final int ENROLL_SAMPLES = 25; // images per student
    private final double THRESHOLD = 75.0; // LBPH distance threshold (tune it)

    // -------------------- App Frame --------------------
    public FaceAttendanceApp() {
        super("Face Attendance System - Reva University");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setResizable(true);
        setLocationRelativeTo(null);

        // Top navigation
        JPanel navPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 0));
        navPanel.setBackground(PRIMARY);
        navPanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        dashboardBtn = createNavButton("Dashboard");
        studentsBtn = createNavButton("Students");
        attendanceBtn = createNavButton("Attendance");
        adminBtn = createNavButton("Admin");

        navPanel.add(dashboardBtn);
        navPanel.add(Box.createHorizontalStrut(20));
        navPanel.add(studentsBtn);
        navPanel.add(Box.createHorizontalStrut(20));
        navPanel.add(attendanceBtn);
        navPanel.add(Box.createHorizontalStrut(20));
        navPanel.add(adminBtn);

        // Content area
        contentCards = new CardLayout();
        contentPanel = new JPanel(contentCards);
        contentPanel.setBackground(BG_COLOR);

        contentPanel.add(createDashboardPanel(), "DASHBOARD");
        contentPanel.add(createStudentsPanel(), "STUDENTS");
        contentPanel.add(createAttendancePanel(), "ATTENDANCE");
        contentPanel.add(createAdminPanel(), "ADMIN");

        // Set initial view
        updateNavButtons(dashboardBtn);
        contentCards.show(contentPanel, "DASHBOARD");

        add(navPanel, BorderLayout.NORTH);
        add(contentPanel, BorderLayout.CENTER);

        // Create a south panel to hold both log panel and footer
        JPanel southPanel = new JPanel(new BorderLayout());
        
        // Create log panel for all interfaces
        GradientPanel logPanel = new GradientPanel(new Color(58, 96, 115), new Color(58, 123, 213));
        logPanel.setLayout(new BorderLayout());
        logPanel.setPreferredSize(new Dimension(0, 120));
        logPanel.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(255, 255, 255, 100), 1),
            "Logs",
            javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
            javax.swing.border.TitledBorder.DEFAULT_POSITION, 
            new Font("Segoe UI", Font.BOLD, 14),
            Color.WHITE
        ));

        // Create a more modern log area
        logArea = new JTextArea(5, 50);
        logArea.setFont(new Font("Consolas", Font.PLAIN, 13));
        logArea.setForeground(new Color(240, 240, 240));
        logArea.setBackground(new Color(10, 25, 45));
        logArea.setCaretColor(Color.WHITE);
        logArea.setEditable(false);

        // Create a custom scroll pane with transparent background
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createEmptyBorder());
        logScroll.getViewport().setBackground(new Color(10, 25, 45));
        logScroll.setOpaque(true);
        logScroll.getViewport().setBackground(new Color(0, 0, 0, 0));

        // Add log scroll to log panel
        logPanel.add(logScroll, BorderLayout.CENTER);

        // Add log panel to south panel
        southPanel.add(logPanel, BorderLayout.NORTH);
         // Add footer panel to south panel
        JPanel footerPanel = createFooterPanel();
        southPanel.add(footerPanel, BorderLayout.SOUTH);
         // Add south panel to main frame
        add(southPanel, BorderLayout.SOUTH);

        setVisible(true);

        // Force gradient repaint to prevent initial overlap glitch
        SwingUtilities.invokeLater(() -> {
            revalidate();
            repaint();
        });
        pack(); // Pack to preferred size

        // Initialize components
        cameraLabel = new JLabel();
        enrollBar = new JProgressBar();

        // DB connect
        connectDB();

        // Prepare dirs
        if (!datasetDir.exists()) datasetDir.mkdirs();
        if (!modelsDir.exists()) modelsDir.mkdirs();

        log("System initialized successfully.");

        // Refresh dashboard statistics on startup
        refreshDashboardStats();

        setVisible(true);
        setExtendedState(JFrame.MAXIMIZED_BOTH); // Maximize window for better official look

        // Add component listener for dynamic resizing
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                revalidate();
                repaint();
            }
        });

        // Start live time update
        timeTimer = new Timer(1000, e -> updateTimeLabel());
        timeTimer.start();
        updateTimeLabel(); // initial update
    }

    private void showTrainingDialog(boolean show) {
        if (show) {
            SwingUtilities.invokeLater(() -> {
                if (trainingDialog == null) {
                    trainingDialog = new JDialog(this, "Training model...", false);
                    JPanel p = new JPanel(new BorderLayout());
                    p.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
                    JProgressBar bar = new JProgressBar();
                    bar.setIndeterminate(true);
                    bar.setStringPainted(true);
                    bar.setString("Training in progress. Please wait...");
                    p.add(new JLabel("Building LBPH model from samples"), BorderLayout.NORTH);
                    p.add(bar, BorderLayout.CENTER);
                    trainingDialog.setContentPane(p);
                    trainingDialog.setSize(360, 120);
                    trainingDialog.setLocationRelativeTo(this);
                }
                trainingDialog.setVisible(true);
            });
        } else {
            SwingUtilities.invokeLater(() -> {
                if (trainingDialog != null) trainingDialog.setVisible(false);
            });
        }
    }

    // -------------------- DB --------------------
    private void connectDB() {
        try {
            // Try to load MySQL driver
            Class.forName("com.mysql.cj.jdbc.Driver");
            
            // Try to connect to MySQL
            try {
                conn = DriverManager.getConnection("jdbc:mysql://localhost:3306/face_recognition_db", "root", "1234");
                log("Connected to MySQL database successfully.");
            } catch (SQLException e) {
                // If MySQL connection fails, try SQLite as fallback
                log("MySQL connection failed: " + e.getMessage() + ". Trying SQLite as fallback...");
                Class.forName("org.sqlite.JDBC");
                String dbPath = new File("face_recognition.db").getAbsolutePath();
                conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                log("Connected to SQLite database: " + dbPath);
            }
            
            try (Statement st = conn.createStatement()) {
                // Create database if not exists
                st.execute("CREATE DATABASE IF NOT EXISTS face_recognition_db");
                st.execute("USE face_recognition_db");

                // Students table with phone and enhanced constraints
                st.execute("CREATE TABLE IF NOT EXISTS students (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "name VARCHAR(100) NOT NULL, " +
                    "rollno VARCHAR(20) NOT NULL UNIQUE, " +
                    "email VARCHAR(100) NOT NULL UNIQUE, " +
                    "phone VARCHAR(15) NOT NULL UNIQUE, " +
                    "class VARCHAR(50) NOT NULL, " +
                    "department VARCHAR(100) NOT NULL, " +
                    "face_id VARCHAR(50) NOT NULL UNIQUE, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")");

                // Add phone column if it doesn't exist (for backward compatibility)
                try {
                    st.execute("ALTER TABLE students ADD COLUMN IF NOT EXISTS phone VARCHAR(15) UNIQUE");
                    // Update existing records to have a default phone if needed
                    st.execute("UPDATE students SET phone = CONCAT('0000000000') WHERE phone IS NULL OR phone = ''");
                } catch (Exception ignore) { /* older MySQL may not support IF NOT EXISTS */ }

                // Face data table
                st.execute("CREATE TABLE IF NOT EXISTS face_data (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "student_id INT NOT NULL, " +
                    "image_path VARCHAR(255) NOT NULL, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "FOREIGN KEY (student_id) REFERENCES students(id) ON DELETE CASCADE" +
                    ")");

                // Attendance table
                st.execute("CREATE TABLE IF NOT EXISTS attendance (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "student_id INT NOT NULL, " +
                    "attendance_date DATE NOT NULL, " +
                    "check_in_time TIME NOT NULL, " +
                    "check_out_time TIME NULL, " +
                    "status ENUM('Present', 'Late', 'Absent') DEFAULT 'Present', " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "FOREIGN KEY (student_id) REFERENCES students(id) ON DELETE CASCADE, " +
                    "UNIQUE KEY unique_attendance (student_id, attendance_date)" +
                    ")");

                // Ensure check_out_time exists for older databases
                try {
                    st.execute("ALTER TABLE attendance ADD COLUMN IF NOT EXISTS check_out_time TIME NULL");
                } catch (Exception ignore) { /* older MySQL may not support IF NOT EXISTS, ignore errors if already present */ }

                // Admin users table for authentication
                st.execute("CREATE TABLE IF NOT EXISTS admins (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "username VARCHAR(50) NOT NULL UNIQUE, " +
                    "password_hash VARCHAR(255) NOT NULL, " +
                    "email VARCHAR(100) NOT NULL UNIQUE, " +
                    "role ENUM('admin', 'faculty') DEFAULT 'admin', " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "last_login TIMESTAMP NULL" +
                    ")");

                // Insert default admin user if not exists
                try {
                    PreparedStatement checkAdmin = conn.prepareStatement("SELECT COUNT(*) FROM admins WHERE username = 'admin'");
                    ResultSet rs = checkAdmin.executeQuery();
                    if (rs.next() && rs.getInt(1) == 0) {
                        // Hash the default password "admin123"
                        String defaultPassword = "admin123";
                        String hashedPassword = hashPassword(defaultPassword);
                        PreparedStatement insertAdmin = conn.prepareStatement(
                            "INSERT INTO admins (username, password_hash, email, role) VALUES (?, ?, ?, 'admin')");
                        insertAdmin.setString(1, "admin");
                        insertAdmin.setString(2, hashedPassword);
                        insertAdmin.setString(3, "admin@institute.edu");
                        insertAdmin.executeUpdate();
                    }
                } catch (Exception ex) {
                    log("Warning: Could not create default admin user: " + ex.getMessage());
                }

                // Audit logs table
                st.execute("CREATE TABLE IF NOT EXISTS audit_logs (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "user_srn VARCHAR(20) NULL, " +
                    "admin_username VARCHAR(50) NULL, " +
                    "action VARCHAR(255) NOT NULL, " +
                    "details TEXT NULL, " +
                    "ip_address VARCHAR(45) NULL, " +
                    "timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "FOREIGN KEY (user_srn) REFERENCES students(rollno) ON DELETE SET NULL" +
                    ")");

                // ---------------- ADD INDEXES (compatible way) ----------------
        String[][] indexes = {
            {"students", "idx_students_rollno", "rollno"},
            {"students", "idx_students_email", "email"},
            {"students", "idx_students_phone", "phone"},
            {"attendance", "idx_attendance_date", "attendance_date"},
            {"attendance", "idx_attendance_student_date", "student_id, attendance_date"},
            {"audit_logs", "idx_audit_logs_timestamp", "timestamp"},
            {"audit_logs", "idx_audit_logs_user", "user_srn"}
        };

        for (String[] idx : indexes) {
            try {
                st.execute("CREATE INDEX " + idx[1] + " ON " + idx[0] + "(" + idx[2] + ")");
            } catch (SQLException ignore) {
                // Ignore "Duplicate key name" error
            }
        }
                // Add additional constraints for data integrity
                try {
                    // Different syntax based on database type (MySQL vs SQLite)
                    String dbType = conn.getMetaData().getDatabaseProductName().toLowerCase();
                    
                    if (dbType.contains("mysql")) {
                        // MySQL syntax (without IF NOT EXISTS which is not supported in all MySQL versions)
                        try {
                            // Ensure check_out_time is after check_in_time
                            st.execute("ALTER TABLE attendance ADD CONSTRAINT chk_checkout_after_checkin " +
                                "CHECK (check_out_time IS NULL OR check_out_time > check_in_time)");
                        } catch (SQLException e) {
                            // Ignore if constraint already exists
                            if (!e.getMessage().contains("Duplicate")) {
                                throw e;
                            }
                        }
                        
                        try {
                            // Ensure valid email format (basic check)
                            st.execute("ALTER TABLE students ADD CONSTRAINT chk_valid_email " +
                                "CHECK (email LIKE '%@%.%')");
                        } catch (SQLException e) {
                            // Ignore if constraint already exists
                            if (!e.getMessage().contains("Duplicate")) {
                                throw e;
                            }
                        }
                        
                        try {
                            // Ensure phone is numeric
                            st.execute("ALTER TABLE students ADD CONSTRAINT chk_phone_numeric " +
                                "CHECK (phone REGEXP '^[0-9]+$')");
                        } catch (SQLException e) {
                            // Ignore if constraint already exists
                            if (!e.getMessage().contains("Duplicate")) {
                                throw e;
                            }
                        }
                    } else if (dbType.contains("sqlite")) {
                        // SQLite doesn't support adding constraints after table creation
                        // We'll handle validation in the application code instead
                        log("Using SQLite - constraints will be enforced in application code");
                    }
                } catch (Exception ex) {
                    log("Warning: Could not add some constraints: " + ex.getMessage());
                }
            }
            log("DB connected and tables ready.");
        } catch (Exception ex) {
            log("DB error: " + ex.getMessage());
        }
    }

    // insert student or get existing id
    private int ensureStudent(String name, String rollno, String email, String phone, String class_, String department) {
        try {
            // try insert
            String ins = "INSERT INTO students (name, rollno, email, phone, class, department, face_id) VALUES (?, ?, ?, ?, ?, ?, ?)";
            PreparedStatement ps = conn.prepareStatement(ins, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, rollno);
            ps.setString(3, email);
            ps.setString(4, phone);
            ps.setString(5, class_);
            ps.setString(6, department);
            ps.setString(7, rollno); // face_id = rollno
            ps.executeUpdate();

            ResultSet rs = ps.getGeneratedKeys();
            if (rs.next()) {
                int id = rs.getInt(1);
                log("Student created: " + name + " id=" + id);
                return id;
            }
        } catch (Exception ex) {
            log("ensureStudent error: " + ex.getMessage() + " — trying to fetch existing student.");
            try {
                PreparedStatement sel = conn.prepareStatement("SELECT id FROM students WHERE rollno = ? OR email = ?");
                sel.setString(1, rollno);
                sel.setString(2, email);
                ResultSet rs2 = sel.executeQuery();
                if (rs2.next()) {
                    int id = rs2.getInt("id");
                    log("Existing student found id=" + id);
                    return id;
                }
            } catch (Exception ex2) {
                log("ensureStudent lookup failed: " + ex2.getMessage());
            }
        }
        return -1;
    }

    private void markAttendanceDB(int studentId) {
        try {
            LocalDate today = LocalDate.now();
            LocalTime time = LocalTime.now();

            // Check if student has an open session (check-in without check-out)
            PreparedStatement openPs = conn.prepareStatement(
                    "SELECT id, attendance_date FROM attendance WHERE student_id = ? AND check_out_time IS NULL ORDER BY attendance_date DESC LIMIT 1");
            openPs.setInt(1, studentId);
            ResultSet openRs = openPs.executeQuery();

            if (openRs.next()) {
                LocalDate openDate = openRs.getDate("attendance_date").toLocalDate();

                // If open session is from today, mark check-out
                if (openDate.equals(today)) {
                    markLogoutDB(studentId); // Mark check-out for today's session
                    return;
                } else {
                    // If open session is from previous day, require logout first
                    log("Cannot check-in: previous session from " + openDate + " is not logged out. Please logout first.");
                    if (!usingRecognitionDialog) {
                        JOptionPane.showMessageDialog(this,
                                "Cannot mark attendance. You have a previous session (" + openDate + ") without logout. Please logout first.",
                                "Logout Required", JOptionPane.WARNING_MESSAGE);
                    }
                    return;
                }
            }

            // No open session, check if already checked in today
            PreparedStatement checkToday = conn.prepareStatement(
                    "SELECT id FROM attendance WHERE student_id = ? AND attendance_date = ?");
            checkToday.setInt(1, studentId);
            checkToday.setDate(2, Date.valueOf(today));
            ResultSet todayRs = checkToday.executeQuery();

            if (todayRs.next()) {
                // Already checked in today, this should be check-out
                markLogoutDB(studentId);
                return;
            }

            // New check-in for today
            PreparedStatement ins = conn.prepareStatement(
                    "INSERT INTO attendance (student_id, attendance_date, check_in_time, status) VALUES (?, ?, ?, 'Present')");
            ins.setInt(1, studentId);
            ins.setDate(2, Date.valueOf(today));
            ins.setTime(3, Time.valueOf(time));
            ins.executeUpdate();

            // get roll & name for log and audit
            PreparedStatement q2 = conn.prepareStatement("SELECT name, rollno FROM students WHERE id = ?");
            q2.setInt(1, studentId);
            ResultSet r2 = q2.executeQuery();
            String name = "", rollno = "";
            if (r2.next()) { name = r2.getString("name"); rollno = r2.getString("rollno"); }

            log("Check-IN marked: " + name + " (" + rollno + ") at " + time);
            auditLog(rollno, null, "Attendance Check-IN", "Date: " + today + ", Time: " + time);
            refreshDashboardStats(); // Update dashboard statistics

        } catch (Exception ex) {
            log("markAttendanceDB error: " + ex.getMessage());
        }
    }

    private void markLogoutDB(int studentId) {
        try {
            LocalDate today = LocalDate.now();
            LocalTime time = LocalTime.now();

            // Ensure a check-in for today exists
            PreparedStatement chk = conn.prepareStatement(
                    "SELECT id FROM attendance WHERE student_id = ? AND attendance_date = ? AND check_out_time IS NULL");
            chk.setInt(1, studentId);
            chk.setDate(2, Date.valueOf(today));
            ResultSet chkRs = chk.executeQuery();
            if (!chkRs.next()) {
                log("Cannot logout: no open check-in found for today.");
                if (!usingRecognitionDialog) {
                    JOptionPane.showMessageDialog(this,
                            "Cannot logout. No open attendance session found for today.",
                            "No Check-In", JOptionPane.WARNING_MESSAGE);
                }
                return;
            }

            // Update check_out_time only if not already set
            PreparedStatement upd = conn.prepareStatement(
                    "UPDATE attendance SET check_out_time = ? WHERE student_id = ? AND attendance_date = ? AND check_out_time IS NULL");
            upd.setTime(1, Time.valueOf(time));
            upd.setInt(2, studentId);
            upd.setDate(3, Date.valueOf(today));
            int updated = upd.executeUpdate();
            if (updated == 0) {
                log("Logout already recorded earlier today for student id=" + studentId);
                if (!usingRecognitionDialog) {
                    JOptionPane.showMessageDialog(this,
                            "Logout already recorded for today.",
                            "Already Logged Out", JOptionPane.INFORMATION_MESSAGE);
                }
                return;
            }

            // get roll & name for log and audit
            PreparedStatement q2 = conn.prepareStatement("SELECT name, rollno FROM students WHERE id = ?");
            q2.setInt(1, studentId);
            ResultSet r2 = q2.executeQuery();
            String name = "", rollno = "";
            if (r2.next()) { name = r2.getString("name"); rollno = r2.getString("rollno"); }

            log("Check-OUT marked: " + name + " (" + rollno + ") at " + time);
            auditLog(rollno, null, "Attendance Check-OUT", "Date: " + today + ", Time: " + time);
            refreshDashboardStats(); // Update dashboard statistics

        } catch (Exception ex) {
            log("markLogoutDB error: " + ex.getMessage());
        }
    }

    // -------------------- Enrollment (capture) --------------------
    private void enrollFlow(int studentId) {
        if (!ensureOpenCvLoaded()) {
            log("OpenCV not available. Cannot enroll without native libraries.");
            return;
        }
        if (!ensureCascadeLoaded()) {
            log("OpenCV not available. Cannot enroll without cascade.");
            return;
        }
        // create folder dataset/{studentId}
        File d = new File(datasetDir, String.valueOf(studentId));
        if (!d.exists()) d.mkdirs();

        log("Starting enrollment for id=" + studentId + ". Look at camera. Collecting " + ENROLL_SAMPLES + " samples.");
        try {
            // reset progress bar
            if (enrollBar != null) {
                SwingUtilities.invokeLater(() -> {
                    enrollBar.setMaximum(ENROLL_SAMPLES);
                    enrollBar.setValue(0);
                    enrollBar.setString("Enrollment progress: 0/" + ENROLL_SAMPLES);
                });
            }

            OpenCVFrameGrabber g = new OpenCVFrameGrabber(0);
            g.start();
            OpenCVFrameConverter.ToMat convToMat = new OpenCVFrameConverter.ToMat();
            Java2DFrameConverter java2d = new Java2DFrameConverter();

            int collected = 0;
            long lastSaved = 0;
            while (collected < ENROLL_SAMPLES) {
                Frame frame = g.grab();
                if (frame == null) continue;
                Mat mat = convToMat.convert(frame);
                if (mat == null) continue;

                Mat gray = new Mat();
                cvtColor(mat, gray, COLOR_BGR2GRAY);
                RectVector faces = new RectVector();
                if (faceCascade != null) {
                    faceCascade.detectMultiScale(gray, faces);
                }

                if (faces.size() > 0) {
                    Rect r = faces.get(0);
                    Mat face = new Mat(gray, r);
                    org.bytedeco.opencv.global.opencv_imgproc.resize(face, face, new Size(200, 200));
                    long now = System.currentTimeMillis();
                    // save at most once per 300 ms to avoid duplicates
                    if (now - lastSaved > 300) {
                        String fname = String.format("%s/%03d.png", (Object) d.getAbsolutePath(), (Object) (collected + 1));
                        imwrite(fname, face);
                        // store path in DB for training
                        try {
                            if (conn != null && !conn.isClosed()) {
                                PreparedStatement fps = conn.prepareStatement(
                                    "INSERT INTO face_data (student_id, image_path) VALUES (?, ?)");
                                fps.setInt(1, studentId);
                                // Normalize to use forward slashes so OpenCV can read consistently
                                String normPath = fname.replace('\\', '/');
                                fps.setString(2, normPath);
                                fps.executeUpdate();
                            }
                        } catch (Exception dbx) {
                            log("Warning: could not insert face_data: " + dbx.getMessage());
                        }
                        collected++;
                        lastSaved = now;
                        int current = collected;
                        log("Saved " + fname + " (" + current + "/" + ENROLL_SAMPLES + ")");
                        if (enrollBar != null) {
                            SwingUtilities.invokeLater(() -> {
                                enrollBar.setValue(current);
                                enrollBar.setString("Enrollment progress: " + current + "/" + ENROLL_SAMPLES);
                            });
                        }
                    }

                    // draw rectangle on preview (so user can align)
                    BufferedImage bi = java2d.convert(convToMat.convert(mat));
                    Graphics2D g2 = bi.createGraphics();
                    g2.setColor(Color.YELLOW);
                    g2.setStroke(new BasicStroke(3));
                    g2.drawRect(r.x(), r.y(), r.width(), r.height());
                    g2.dispose();
                    SwingUtilities.invokeLater(() -> updateCameraView(bi));
                } else {
                    BufferedImage bi = java2d.convert(convToMat.convert(mat));
                    SwingUtilities.invokeLater(() -> updateCameraView(bi));
                }
                Thread.sleep(80); // small delay
            }

            g.stop();
            log("Enrollment complete for id=" + studentId);
            // finalize progress bar
            if (enrollBar != null) {
                SwingUtilities.invokeLater(() -> {
                    enrollBar.setValue(ENROLL_SAMPLES);
                    enrollBar.setString("Enrollment complete: " + ENROLL_SAMPLES + "/" + ENROLL_SAMPLES);
                });
            }
            // Hide camera panel after enrollment
            SwingUtilities.invokeLater(() -> {
                cameraLabel.setIcon(null);
            });
            // Auto-train the model after enrollment
            new Thread(() -> trainModel()).start();
        } catch (Exception ex) {
            log("Enroll error: " + ex.getMessage());
        }
    }

    // -------------------- Training --------------------
    private void trainModel() {
        log("Training started...");
        try {
            showTrainingDialog(true);
            if (!ensureOpenCvLoaded()) {
                log("OpenCV not available. Cannot train without native libraries.");
                showTrainingDialog(false);
                return;
            }
            // collect image paths and labels
            List<Mat> images = new ArrayList<>();
            List<Integer> labels = new ArrayList<>();

            try {
                PreparedStatement ps = conn.prepareStatement("SELECT student_id, image_path FROM face_data");
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    int label = rs.getInt("student_id");
                    String path = rs.getString("image_path");
                    Mat img = imread(path, IMREAD_GRAYSCALE);
                    if (img != null && !img.empty()) {
                        images.add(img);
                        labels.add(Integer.valueOf(label));
                    }
                }
            } catch (Exception ex) {
                log("Error loading face data: " + ex.getMessage());
                showTrainingDialog(false);
                return;
            }

            if (images.isEmpty()) {
                log("No valid images found for training.");
                showTrainingDialog(false);
                return;
            }

            MatVector matVec = new MatVector(images.size());
            for (int i = 0; i < images.size(); i++) matVec.put(i, images.get(i));
            IntPointer labelsPointer = new IntPointer(labels.size());
            for (int i = 0; i < labels.size(); i++) labelsPointer.put(i, labels.get(i));

            // Create labels Mat (rows = number of images, 1 column, 32-bit signed int)
            Mat labelsMat = new Mat(labels.size(), 1,
                    org.bytedeco.opencv.global.opencv_core.CV_32SC1,
                    labelsPointer);

            LBPHFaceRecognizer recognizer = LBPHFaceRecognizer.create();
            recognizer.train(matVec, labelsMat);
            recognizer.save(modelFile);

            log("Training finished. Model saved to " + modelFile);
        } catch (Exception ex) {
            log("Training error: " + ex.getMessage());
        } finally {
            showTrainingDialog(false);
        }
    }

    private void openRecognitionDialog(boolean isCheckout) {
        if (recognitionRunning) {
            JOptionPane.showMessageDialog(this, "Camera is already running.");
            return;
        }
        if (!new File(modelFile).exists()) {
            JOptionPane.showMessageDialog(this, "Model not found. Train first.");
            return;
        }

        checkoutMode = isCheckout;
        stopAfterFirstRecognize = true; // auto-close but with 5s hold
        recognitionRunning = true;
        waitingToClose = false;
        closeAtMillis = 0L;
        usingRecognitionDialog = true;

        recognitionLabel = new JLabel();
        recognitionLabel.setPreferredSize(new Dimension(800, 600));
        recognitionDialog = new JDialog(this, isCheckout ? "Check-OUT - Face Recognition" : "Check-IN - Face Recognition", false);

        GradientPanel content = new GradientPanel(PRIMARY, PRIMARY.darker());
        content.setLayout(new BorderLayout(10, 10));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JLabel info = new JLabel(isCheckout ? "Look at the camera to check-out." : "Look at the camera to check-in.");
        info.setFont(new Font("Segoe UI", Font.BOLD, 16));
        info.setForeground(Color.WHITE);
        JPanel top = new JPanel();
        top.setOpaque(false);
        top.add(info);
        content.add(top, BorderLayout.NORTH);

        JPanel videoWrap = new JPanel(new BorderLayout());
        videoWrap.setOpaque(false);
        videoWrap.add(recognitionLabel, BorderLayout.CENTER);
        content.add(videoWrap, BorderLayout.CENTER);

        JButton closeBtn = new JButton("Close");
        closeBtn.setBackground(new Color(244, 67, 54));
        closeBtn.setFont(new Font("Segoe UI", Font.BOLD, 16));
        closeBtn.setBorder(BorderFactory.createEmptyBorder(8, 15, 8, 15));
        closeBtn.setForeground(Color.WHITE);
        closeBtn.setContentAreaFilled(false);
        closeBtn.setOpaque(false);
        closeBtn.setUI(new GradientButtonUI(new Color(244, 67, 54), new Color(200, 40, 40)));
        closeBtn.addActionListener(ev -> {
            recognitionRunning = false;
            safeStopGrabber();
            if (recognitionDialog != null) recognitionDialog.dispose();
        });
        JPanel south = new JPanel();
        south.setOpaque(false);
        south.add(closeBtn);
        content.add(south, BorderLayout.SOUTH);

        recognitionDialog.setContentPane(content);
        recognitionDialog.pack();
        recognitionDialog.setLocationRelativeTo(this);
        recognitionDialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                recognitionRunning = false;
                safeStopGrabber();
                usingRecognitionDialog = false;
                waitingToClose = false;
                closeAtMillis = 0L;
                cameraLabel = null;
                recognitionLabel = null;
            }
        });
        recognitionDialog.setVisible(true);

        // Redirect camera output to the dialog's label and start recognition
        this.cameraLabel = recognitionLabel;
        new Thread(() -> runRecognition()).start();
    }

    // -------------------- Recognition --------------------
    private void runRecognition() {
        log("Recognition started. Press Stop to end.");
        if (!ensureOpenCvLoaded()) {
            log("OpenCV not available. Cannot recognize without native libraries.");
            return;
        }
        LBPHFaceRecognizer recognizer = LBPHFaceRecognizer.create();
        try {
            recognizer.read(modelFile);
        } catch (Exception ex) {
            log("Cannot read model: " + ex.getMessage());
            return;
        }

        if (!ensureCascadeLoaded()) {
            log("OpenCV not available. Cannot run recognition without cascade.");
            return;
        }

        boolean recognizedThisSession = false;
        try {
            grabber = new OpenCVFrameGrabber(0);
            grabber.start();
            OpenCVFrameConverter.ToMat convToMat = new OpenCVFrameConverter.ToMat();
            Java2DFrameConverter java2d = new Java2DFrameConverter();

            boolean actionDoneThisSession = false;
            while (recognitionRunning) {
                Frame frame = grabber.grab();
                if (frame == null) continue;
                Mat mat = convToMat.convert(frame);
                if (mat == null) continue;

                Mat gray = new Mat();
                cvtColor(mat, gray, COLOR_BGR2GRAY);
                RectVector faces = new RectVector();
                if (faceCascade != null) {
                    faceCascade.detectMultiScale(gray, faces);
                }

                BufferedImage bi = java2d.convert(convToMat.convert(mat));
                Graphics2D g2 = bi.createGraphics();
                g2.setStroke(new BasicStroke(2));
                g2.setFont(new Font("Arial", Font.BOLD, 18));

                for (int i = 0; i < faces.size(); i++) {
                    Rect r = faces.get(i);
                    Mat face = new Mat(gray, r);
                    org.bytedeco.opencv.global.opencv_imgproc.resize(face, face, new Size(200, 200));

                    IntPointer label = new IntPointer(1);
                    DoublePointer confidence = new DoublePointer(1);
                    recognizer.predict(face, label, confidence);
                    int predictedId = label.get(0);
                    double conf = confidence.get(0);

                    String text = "Unknown";
                    if (predictedId > 0 && conf < THRESHOLD) {
                        // map id -> name, rollno
                        PreparedStatement q = conn.prepareStatement("SELECT name, rollno FROM students WHERE id = ?");
                        q.setInt(1, predictedId);
                        ResultSet rs = q.executeQuery();
                        if (rs.next()) {
                            String name = rs.getString("name");
                            String rollno = rs.getString("rollno");
                            text = name + " (" + rollno + ")";
                            // mark attendance or logout (async) only once per session
                            if (!actionDoneThisSession) {
                                final int idToMark = predictedId;
                                actionDoneThisSession = true;
                                if (checkoutMode) {
                                    SwingUtilities.invokeLater(() -> markLogoutDB(idToMark));
                                } else {
                                    SwingUtilities.invokeLater(() -> markAttendanceDB(idToMark));
                                }
                            }
                            if (stopAfterFirstRecognize) {
                                recognizedThisSession = true;
                                if (!waitingToClose) {
                                    waitingToClose = true;
                                    closeAtMillis = System.currentTimeMillis() + 5000L; // keep window open for 5 seconds
                                }
                                // Do not stop immediately; the loop will continue rendering until the timer elapses
                            }
                        }
                    } else {
                        text = String.format(Locale.US, "Unknown (%.1f)", Double.valueOf(conf));
                    }

                    // draw box + label
                    g2.setColor(Color.GREEN);
                    g2.drawRect(r.x(), r.y(), r.width(), r.height());
                    g2.setColor(Color.YELLOW);
                    g2.drawString(text, Math.max(0, r.x()), Math.max(0, r.y() - 8));
                }

                // Show countdown overlay if we are in the 5-second hold window
                if (waitingToClose && stopAfterFirstRecognize) {
                    long remain = Math.max(0L, closeAtMillis - System.currentTimeMillis());
                    String msg = "Recognized. Closing in " + ((remain / 1000L) + 1) + "s";
                    g2.setColor(new Color(0, 0, 0, 160));
                    g2.fillRoundRect(10, 10, 260, 36, 12, 12);
                    g2.setColor(Color.WHITE);
                    g2.drawString(msg, 20, 36);
                }

                g2.dispose();
                SwingUtilities.invokeLater(() -> updateCameraView(bi));

                // If recognized and hold time elapsed, stop recognition
                if (waitingToClose && stopAfterFirstRecognize && System.currentTimeMillis() >= closeAtMillis) {
                    recognitionRunning = false;
                }

                Thread.sleep(80);
            }
            safeStopGrabber();
            // Cleanup: close recognition dialog if used
            if (usingRecognitionDialog) {
                final boolean finalRecognized = recognizedThisSession;
                SwingUtilities.invokeLater(() -> {
                    try {
                        if (recognitionDialog != null) {
                            recognitionDialog.dispose();
                        }
                    } catch (Exception ignore) {}
                    cameraLabel = null;
                    recognitionLabel = null;
                    usingRecognitionDialog = false;
                    waitingToClose = false;
                    closeAtMillis = 0L;
                    log(finalRecognized ? "Recognition finished and attendance marked. Camera closed." : "Recognition stopped.");
                });
            }
        } catch (Exception ex) {
            log("Recognition error: " + ex.getMessage());
        }
    }

    private void safeStopGrabber() {
        try {
            if (grabber != null) {
                grabber.stop();
                grabber.release();
                grabber = null;
            }
        } catch (Exception ignored) {}
    }

    private void log(String s) {
        SwingUtilities.invokeLater(() -> {
            String msg = "[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "] " + s + "\n";
            logArea.append(msg);
            if (statusBar != null) statusBar.setText(s);
        });
    }

    private void updateTimeLabel() {
        LocalDateTime now = LocalDateTime.now();
        SwingUtilities.invokeLater(() -> {
            if (dayLabel != null) {
                dayLabel.setText(now.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault())));
            }
            if (dateLabel != null) {
                dateLabel.setText(now.format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.getDefault())));
            }
            if (timeLabel != null) {
                timeLabel.setText(now.format(DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault())));
            }
        });
    }

    // -------------------- Security & Utility Methods --------------------

    private String hashPassword(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            log("Error hashing password: " + e.getMessage());
            return password; // fallback (not secure)
        }
    }

    private void auditLog(String userSrn, String adminUsername, String action, String details) {
        try {
            PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO audit_logs (user_srn, admin_username, action, details, ip_address) VALUES (?, ?, ?, ?, ?)");
            ps.setString(1, userSrn);
            ps.setString(2, adminUsername);
            ps.setString(3, action);
            ps.setString(4, details);
            ps.setString(5, "localhost"); // For now, using localhost
            ps.executeUpdate();
        } catch (Exception ex) {
            log("Audit log error: " + ex.getMessage());
        }
    }

    // Scales and centers the camera frame to fit the current label size while preserving aspect ratio
    private void updateCameraView(BufferedImage bi) {
        if (bi == null || cameraLabel == null) {
            if (cameraLabel != null) {
                cameraLabel.setIcon(null);
            }
            lastCameraFrame = null;
            return;
        }
        lastCameraFrame = bi;
        int w = cameraLabel.getWidth();
        int h = cameraLabel.getHeight();
        if (w <= 0 || h <= 0) {
            cameraLabel.setIcon(new ImageIcon(bi));
            return;
        }
        double arSrc = bi.getWidth() / (double) bi.getHeight();
        double arDst = w / (double) h;
        int drawW, drawH;
        if (arSrc > arDst) {
            drawW = w;
            drawH = (int) Math.max(1, Math.round(w / arSrc));
        } else {
            drawH = h;
            drawW = (int) Math.max(1, Math.round(h * arSrc));
        }
        int x = (w - drawW) / 2;
        int y = (h - drawH) / 2;

        BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, w, h);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(bi, x, y, drawW, drawH, null);
        } finally {
            g.dispose();
        }
        cameraLabel.setIcon(new ImageIcon(canvas));
    }

    private boolean ensureCascadeLoaded() {
        try {
            if (faceCascade != null && !faceCascade.empty()) return true;

            // Ensure OpenCV native libraries are extracted and loaded
            if (!ensureOpenCvLoaded()) return false;

            // Resolve absolute path for the cascade to handle different working directories
            String resolvedPath = resolveCascadePath();
            CascadeClassifier cc = new CascadeClassifier(resolvedPath);
            if (cc.empty()) {
                log("ERROR: Could not load cascade from: " + new java.io.File(resolvedPath).getAbsolutePath() +
                        " — ensure the file exists. Working dir: " + System.getProperty("user.dir"));
                return false;
            }
            faceCascade = cc;
            log("Cascade loaded: " + new java.io.File(resolvedPath).getAbsolutePath());
            return true;
        } catch (Throwable t) {
            log("OpenCV init error: " + t.getMessage());
            return false;
        }
    }

    // Attempts to resolve the cascade path across common layouts when running from different directories
    private String resolveCascadePath() {
        java.util.List<String> candidates = new java.util.ArrayList<>();
        // as provided
        candidates.add(cascadeFile);
        // CWD + cascade
        candidates.add(new java.io.File(System.getProperty("user.dir"), cascadeFile).getPath());
        // Project layout when running from repo root
        candidates.add("FaceAttendance/FaceAttendance/" + cascadeFile);
        candidates.add("FaceAttendance\\FaceAttendance\\" + cascadeFile);

        for (String p : candidates) {
            java.io.File f = new java.io.File(p);
            if (f.exists() && f.isFile()) {
                return f.getAbsolutePath();
            }
        }
        // Fallback to original (likely to fail, but logs will show absolute attempted path)
        return new java.io.File(cascadeFile).getAbsolutePath();
    }

    private boolean ensureOpenCvLoaded() {
        try {
            // Try loading a core module; subsequent loads will be no-ops
            Loader.load(org.bytedeco.opencv.global.opencv_core.class);
            Loader.load(org.bytedeco.opencv.global.opencv_imgproc.class);
            Loader.load(org.bytedeco.opencv.global.opencv_objdetect.class);
            Loader.load(org.bytedeco.opencv.global.opencv_imgcodecs.class);
            Loader.load(org.bytedeco.opencv.opencv_face.LBPHFaceRecognizer.class);
            return true;
        } catch (Throwable nt) {
            log("OpenCV native load failed: " + nt.getMessage());
            log("Hint: Install 'Microsoft Visual C++ 2015-2022 x64 Redistributable' and restart.");
            return false;
        }
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(new FlatLightLaf());
        } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> {
            FaceAttendanceApp app = new FaceAttendanceApp();
            app.setVisible(true);
        });
    }

    private void openRegisterDialog() {
        // Use JDialog with custom gradient background
        JDialog dialog = new JDialog(this, "Register Student", true);
        GradientPanel gradientPanel = new GradientPanel(PRIMARY, PRIMARY.darker());
        gradientPanel.setLayout(new GridBagLayout());
        dialog.setContentPane(gradientPanel);


        // Gradient background panel
        GradientPanel contentPanel = new GradientPanel(new Color(102, 153, 255), new Color(204, 229, 255));
        contentPanel.setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(10, 10, 10, 10);
        gbc.fill = GridBagConstraints.HORIZONTAL;

       // Fields
        JTextField nameField = new JTextField(20);
        nameField.setFont(new Font("Segoe UI", Font.PLAIN, 16));
        JTextField rollnoField = new JTextField(20);
        rollnoField.setFont(new Font("Segoe UI", Font.PLAIN, 16));
        JTextField emailField = new JTextField(20);
        emailField.setFont(new Font("Segoe UI", Font.PLAIN, 16));
        JTextField phoneField = new JTextField(20);
        phoneField.setFont(new Font("Segoe UI", Font.PLAIN, 16));

        // Department combo
        JComboBox<String> deptCombo = new JComboBox<>(new String[]{
            //"Select Department",
            "Computer Science",
            "Information Technology",
            "Electronics and Communication",
            "Electrical and Electronics",
            "Mechanical Engineering",
            "Civil Engineering",
            "MBA",
            "MCA",
            "BCA",
            "Commerce",
            "Science",
            "Arts"});
        deptCombo.setFont(new Font("Segoe UI", Font.PLAIN, 16));
        deptCombo.setBackground(Color.WHITE);
        deptCombo.setForeground(Color.BLACK);
        deptCombo.setPreferredSize(new Dimension(220, 35));
        
        // Class combo
        JComboBox<String> classCombo = new JComboBox<>(new String[]{"I Year", "II Year", "III Year", "IV Year"});
        classCombo.setFont(new Font("Segoe UI", Font.PLAIN, 16));
        classCombo.setBackground(Color.WHITE);
        classCombo.setForeground(Color.BLACK);
        classCombo.setPreferredSize(new Dimension(220, 35));
        
        // Section combo - will be populated based on department
        JComboBox<String> sectionCombo = new JComboBox<>();
        sectionCombo.setFont(new Font("Segoe UI", Font.PLAIN, 16));
        sectionCombo.setBackground(Color.WHITE);
        sectionCombo.setForeground(Color.BLACK);
        sectionCombo.setPreferredSize(new Dimension(220, 35));

       // Section options based on department
        Map<String, String[]> sectionMap = new HashMap<>();
        sectionMap.put("Computer Science", new String[]{"A", "B", "C","D"});
        sectionMap.put("Information Technology", new String[]{"A", "B", "C","D"});
        sectionMap.put("Electronics and Communication", new String[]{"A", "B", "C","D"});
        sectionMap.put("Electrical and Electronics", new String[]{"A", "B", "C","D"});
        sectionMap.put("Civil Engineering", new String[]{"A", "B", "C","D"});
        sectionMap.put("Mechanical Engineering", new String[]{"A", "B", "C","D"});
        sectionMap.put("MBA", new String[]{"A", "B", "C","D"});
        sectionMap.put("MCA", new String[]{"A", "B", "C","D"});
        sectionMap.put("BCA", new String[]{"A", "B", "C","D"});
        sectionMap.put("Commerce", new String[]{"A", "B", "C","D"});
        sectionMap.put("Science", new String[]{"A", "B", "C","D"});
        sectionMap.put("Arts", new String[]{"A", "B", "C","D"});

        // Initially populate section combo
        String initialDept = (String) deptCombo.getSelectedItem();
        for (String sec : sectionMap.get(initialDept)) {
            sectionCombo.addItem(sec);
        }

        // Add listener to update section based on department
        deptCombo.addActionListener(e -> {
            String selectedDept = (String) deptCombo.getSelectedItem();
            sectionCombo.removeAllItems();
            for (String sec : sectionMap.get(selectedDept)) {
                sectionCombo.addItem(sec);
            }
        });

        // Labels with bold font
        Font labelFont = new Font("Segoe UI", Font.BOLD, 20);
        JLabel nameLabel = new JLabel("Name:"); nameLabel.setFont(labelFont);
        JLabel rollLabel = new JLabel("Roll No (SRN21CS001):"); rollLabel.setFont(labelFont);
        JLabel emailLabel = new JLabel("Email (@gmail.com):"); emailLabel.setFont(labelFont);
        JLabel phoneLabel = new JLabel("Phone (+91):"); phoneLabel.setFont(labelFont);
        JLabel deptLabel = new JLabel("Department:"); deptLabel.setFont(labelFont);
        JLabel classLabel = new JLabel("Class:"); classLabel.setFont(labelFont);
        JLabel sectionLabel = new JLabel("Section:"); sectionLabel.setFont(labelFont);

         // Buttons with modern colors
        JButton captureBtn = new JButton("Capture Face");
        captureBtn.setBackground(new Color(76, 175, 80));
        captureBtn.setFont(new Font("Segoe UI", Font.BOLD, 16));
        captureBtn.setForeground(Color.WHITE);
        captureBtn.setBorder(BorderFactory.createEmptyBorder(8, 15, 8, 15));
        captureBtn.setContentAreaFilled(false);
        captureBtn.setOpaque(false);
        captureBtn.setUI(new GradientButtonUI(new Color(76, 175, 80), new Color(56, 155, 60)));


        JButton cancelBtn = new JButton("Cancel");
        cancelBtn.setBackground(new Color(244, 67, 54));
        cancelBtn.setFont(new Font("Segoe UI", Font.BOLD, 16));
        cancelBtn.setBorder(BorderFactory.createEmptyBorder(8, 15, 8, 15));
        cancelBtn.setForeground(Color.WHITE);
        cancelBtn.setContentAreaFilled(false);
        cancelBtn.setOpaque(false);
        cancelBtn.setUI(new GradientButtonUI(new Color(244, 67, 54), new Color(200, 40, 40)));


        // Add labels & fields
        gbc.gridx = 0; gbc.gridy = 0;
        contentPanel.add(nameLabel, gbc);
        gbc.gridx = 1;
        contentPanel.add(nameField, gbc);

        gbc.gridx = 0; gbc.gridy = 1;
        contentPanel.add(rollLabel, gbc);
        gbc.gridx = 1;
        contentPanel.add(rollnoField, gbc);

        gbc.gridx = 0; gbc.gridy = 2;
        contentPanel.add(emailLabel, gbc);
        gbc.gridx = 1;
        contentPanel.add(emailField, gbc);

        gbc.gridx = 0; gbc.gridy = 3;
        contentPanel.add(phoneLabel, gbc);
        gbc.gridx = 1;
        contentPanel.add(phoneField, gbc);

        gbc.gridx = 0; gbc.gridy = 5;
        contentPanel.add(deptLabel, gbc);
        gbc.gridx = 1;
        contentPanel.add(deptCombo, gbc);

        gbc.gridx = 0; gbc.gridy = 4;
        contentPanel.add(classLabel, gbc);
        gbc.gridx = 1;
        contentPanel.add(classCombo, gbc);

        gbc.gridx = 0; gbc.gridy = 6;
        contentPanel.add(sectionLabel, gbc);
        gbc.gridx = 1;
        contentPanel.add(sectionCombo, gbc);

        // Buttons row
        gbc.gridx = 0; gbc.gridy = 7; gbc.gridwidth = 2;
        JPanel btnPanel = new JPanel();
        btnPanel.setOpaque(false); // transparent to show gradient
        btnPanel.add(captureBtn);
        btnPanel.add(cancelBtn);
        contentPanel.add(btnPanel, gbc);

        dialog.setContentPane(contentPanel);
        dialog.pack();
        dialog.setLocationRelativeTo(this);

        // Button actions
        captureBtn.addActionListener(e -> {
            String name = nameField.getText().trim();
            String rollno = rollnoField.getText().trim();
            String email = emailField.getText().trim();
            String phone = phoneField.getText().trim();
            String dept = (String) deptCombo.getSelectedItem();
            String class_ = (String) classCombo.getSelectedItem();
            String section = (String) sectionCombo.getSelectedItem();

            // Comprehensive input validation
            if (name.isEmpty() || rollno.isEmpty() || email.isEmpty() || phone.isEmpty() || class_.isEmpty() || dept.isEmpty()) {
                JOptionPane.showMessageDialog(dialog, "All fields are required.");
                return;
            }

            // Validate SRN pattern (e.g., R21CS001)
            if (!rollno.matches("SRN\\d{2}[A-Z]{2}\\d{3}")) {
                JOptionPane.showMessageDialog(dialog, "Invalid Roll No format. Use pattern: SRN21CS001");
                return;
            }

            // Validate email
            if (!email.matches("^[a-zA-Z0-9._%+-]+@gmail\\.com$")) {
                JOptionPane.showMessageDialog(dialog, "Email must be a valid @gmail.com address.");
                return;
            }

            // Validate phone (exactly 10 digits)
            if (!phone.matches("\\d{10}")) {
                JOptionPane.showMessageDialog(dialog, "Phone number must be exactly 10 digits.");
                return;
            }


            // Comprehensive duplicate registration protection
            try {
                // Check for duplicate SRN, Email, or Phone
                PreparedStatement exist = conn.prepareStatement(
                    "SELECT rollno, email, phone FROM students WHERE rollno = ? OR email = ? OR phone = ?");
                exist.setString(1, rollno);
                exist.setString(2, email);
                exist.setString(3, phone);
                ResultSet er = exist.executeQuery();

                if (er.next()) {
                    String duplicateField = "";
                    if (er.getString("rollno") != null && er.getString("rollno").equals(rollno)) {
                        duplicateField = "Roll Number (SRN)";
                    } else if (er.getString("email") != null && er.getString("email").equals(email)) {
                        duplicateField = "Email";
                    } else if (er.getString("phone") != null && er.getString("phone").equals(phone)) {
                        duplicateField = "Phone Number";
                    }

                    JOptionPane.showMessageDialog(dialog,
                            "Student already registered with given " + duplicateField + "!",
                            "Duplicate Registration", JOptionPane.WARNING_MESSAGE);
                    return;
                }

                // Check for face similarity (if face data exists for this SRN)
                PreparedStatement faceCheck = conn.prepareStatement(
                    "SELECT COUNT(*) c FROM face_data fd JOIN students s ON fd.student_id = s.id WHERE s.rollno = ?");
                faceCheck.setString(1, rollno);
                ResultSet fr = faceCheck.executeQuery();
                if (fr.next() && fr.getInt("c") > 0) {
                    JOptionPane.showMessageDialog(dialog,
                            "Face already exists in system. Duplicate registration not allowed.",
                            "Face Already Registered", JOptionPane.WARNING_MESSAGE);
                    return;
                }

            } catch (Exception ex) {
                log("Error checking duplicates: " + ex.getMessage());
                JOptionPane.showMessageDialog(dialog, "Error validating registration data.");
                return;
            }

            dialog.dispose();
            // Log the registration attempt
            auditLog(null, null, "Student Registration Attempt", "Name: " + name + ", SRN: " + rollno);
            // Open a separate enrollment window (camera) like recognition
            openEnrollmentDialog(name, rollno, email, phone, class_ + " " + section, dept);
        });

        cancelBtn.addActionListener(e -> dialog.dispose());
        dialog.setVisible(true);
    }


    private void openEnrollmentDialog(String name, String rollno, String email, String phone, String class_, String dept) {
        // Create dedicated enrollment dialog
        JDialog dialog = new JDialog(this, "Capture Face - Enrollment", false);
        GradientPanel content = new GradientPanel(PRIMARY, PRIMARY.darker());
        content.setLayout(new BorderLayout(10, 10));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JLabel info = new JLabel("Look at the camera to capture 25 samples.");
        info.setFont(new Font("Segoe UI", Font.BOLD, 16));
        info.setForeground(Color.WHITE);
        JPanel top = new JPanel();
        top.setOpaque(false);
        top.add(info);
        content.add(top, BorderLayout.NORTH);

        JLabel videoLabel = new JLabel();
        videoLabel.setPreferredSize(new Dimension(800, 600));
        JPanel videoWrap = new JPanel(new BorderLayout());
        videoWrap.setOpaque(false);
        videoWrap.add(videoLabel, BorderLayout.CENTER);
        content.add(videoWrap, BorderLayout.CENTER);

        JProgressBar progress = new JProgressBar(0, ENROLL_SAMPLES);
        progress.setStringPainted(true);
        progress.setPreferredSize(new Dimension(800, 25)); // width=800, height=25
        progress.setMinimumSize(new Dimension(800, 25));
        progress.setMaximumSize(new Dimension(800, 25));
        progress.setValue(0);
        progress.setString("Enrollment progress: 0/" + ENROLL_SAMPLES);
        progress.setForeground(new Color(76, 175, 80)); // Green progress
        progress.setBackground(new Color(230, 230, 230));
        progress.setBorder(BorderFactory.createLineBorder(new Color(180, 180, 180), 1));

        JButton closeBtn = new JButton("Close");
        closeBtn.setBackground(new Color(244, 67, 54));
        closeBtn.setFont(new Font("Segoe UI", Font.BOLD, 16));
        closeBtn.setForeground(Color.WHITE);
        closeBtn.setContentAreaFilled(false);
        closeBtn.setBorder(BorderFactory.createEmptyBorder(8, 15, 8, 15));
        closeBtn.setOpaque(false);
        closeBtn.setUI(new GradientButtonUI(new Color(244, 67, 54), new Color(200, 40, 40)));
        closeBtn.addActionListener(ev -> dialog.dispose());

        JPanel progressWrap = new JPanel(new FlowLayout(FlowLayout.CENTER));
        progressWrap.setOpaque(false);
        progressWrap.add(progress);

        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        south.add(progress, BorderLayout.CENTER);
        JPanel right = new JPanel(); right.setOpaque(false); right.add(closeBtn);
        south.add(right, BorderLayout.EAST);
        content.add(south, BorderLayout.SOUTH);

        dialog.setContentPane(content);
        dialog.pack();
        dialog.setLocationRelativeTo(this);

        // Redirect camera output for enrollment to the dialog's label and progress bar
        JLabel prevLabel = this.cameraLabel;
        JProgressBar prevBar = this.enrollBar;
        this.cameraLabel = videoLabel;
        this.enrollBar = progress;

        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                // Restore references when window closes
                cameraLabel = prevLabel;
                enrollBar = prevBar;
            }
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                cameraLabel = prevLabel;
                enrollBar = prevBar;
            }
        });

        dialog.setVisible(true);

        new Thread(() -> {
            try {
                int id = ensureStudent(name, rollno, email, phone, class_, dept);
                if (id > 0) {
                    // Log successful registration
                    auditLog(rollno, null, "Student Registration Completed", "Face enrollment started");
                    refreshDashboardStats(); // Update dashboard statistics after registration
                    enrollFlow(id);
                    // Refresh students table after successful enrollment with a small delay to ensure DB commit
                    SwingUtilities.invokeLater(() -> {
                        try {
                            Thread.sleep(500); // Small delay to ensure database operations complete
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        refreshStudentsTable();
                        log("Students table refreshed after registration.");
                    });
                }
            } finally {
                SwingUtilities.invokeLater(() -> {
                    try { dialog.dispose(); } catch (Exception ignore) {}
                    cameraLabel = prevLabel;
                    enrollBar = prevBar;
                });
            }
        }).start();
    }

    private void openViewAttendanceDialog() {
        JDialog dialog = new JDialog(this, "View Attendance", false);
        dialog.setSize(900, 600);
        dialog.setLocationRelativeTo(this);

        // Gradient background
        GradientPanel mainPanel = new GradientPanel(PRIMARY, PRIMARY.darker());
        mainPanel.setLayout(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        dialog.setContentPane(mainPanel);

        // Title Label
        JLabel titleLabel = new JLabel("Attendance Records", JLabel.CENTER);
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 24));
        titleLabel.setForeground(Color.WHITE);
        mainPanel.add(titleLabel, BorderLayout.NORTH);

        // Table model and JTable with S.No column first
        DefaultTableModel model = new DefaultTableModel(
                new String[]{"S.No", "Name", "Roll No", "Class", "Department", "Date", "Check-In", "Check-Out", "Status"}, 0
        );
        JTable table = new JTable(model);
        table.setFillsViewportHeight(true);
        table.setRowHeight(25);
        table.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        table.getTableHeader().setFont(new Font("Segoe UI", Font.BOLD, 16));
        table.getTableHeader().setOpaque(true);
        table.getTableHeader().setBackground(new Color(33, 150, 243));
        table.getTableHeader().setForeground(Color.WHITE);
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setResizingAllowed(false);

        // Scroll pane with padding
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createLineBorder(Color.WHITE, 2));
        mainPanel.add(scroll, BorderLayout.CENTER);

        // Close button panel
        JPanel buttonPanel = new JPanel();
        buttonPanel.setOpaque(false);

        JButton pdfBtn = new JButton("Download PDF");
        pdfBtn.setFont(new Font("Segoe UI", Font.BOLD, 16));
        pdfBtn.setBackground(new Color(41, 98, 255));
        pdfBtn.setForeground(Color.WHITE);
        pdfBtn.setBorder(BorderFactory.createEmptyBorder(8, 15, 8, 15));
        pdfBtn.setContentAreaFilled(false);
        pdfBtn.setOpaque(false);
        pdfBtn.setUI(new GradientButtonUI(new Color(41, 98, 255), new Color(30, 80, 200)));
        pdfBtn.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setSelectedFile(new File("attendance-" + java.time.LocalDate.now() + ".pdf"));
            int res = chooser.showSaveDialog(dialog);
            if (res == JFileChooser.APPROVE_OPTION) {
                File file = chooser.getSelectedFile();
                String path = file.getAbsolutePath();
                if (!path.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                    file = new File(path + ".pdf");
                }
                exportAttendancePdf(file);
            }
        });

        JButton closeBtn = new JButton("Close");
        closeBtn.setBackground(new Color(244, 67, 54));
        closeBtn.setFont(new Font("Segoe UI", Font.BOLD, 16));
        closeBtn.setBorder(BorderFactory.createEmptyBorder(8, 15, 8, 15));
        closeBtn.setForeground(Color.WHITE);
        closeBtn.setContentAreaFilled(false);
        closeBtn.setOpaque(false);
        closeBtn.setUI(new GradientButtonUI(new Color(244, 67, 54), new Color(200, 40, 40)));
        closeBtn.addActionListener(e -> dialog.dispose());

        buttonPanel.add(pdfBtn);
        buttonPanel.add(closeBtn);
        mainPanel.add(buttonPanel, BorderLayout.SOUTH);

        // Load data from DB and fill with serial numbers
        try {
            PreparedStatement ps = conn.prepareStatement(
                    "SELECT s.name, s.rollno, s.class, s.department, a.attendance_date, a.check_in_time, a.check_out_time, a.status " +
                            "FROM attendance a JOIN students s ON a.student_id = s.id ORDER BY a.attendance_date DESC, a.check_in_time DESC"
            );
            ResultSet rs = ps.executeQuery();
            int i = 1;
            while (rs.next()) {
                model.addRow(new Object[]{
                        i++,
                        rs.getString("name"),
                        rs.getString("rollno"),
                        rs.getString("class"),
                        rs.getString("department"),
                        rs.getDate("attendance_date"),
                        rs.getTime("check_in_time"),
                        rs.getTime("check_out_time"),
                        rs.getString("status")
                });
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Error loading attendance: " + ex.getMessage());
        }

        dialog.setVisible(true);
    }

    private void exportAttendancePdf(File file) {
        try {
            com.lowagie.text.Document document = new com.lowagie.text.Document(com.lowagie.text.PageSize.A4.rotate());
            com.lowagie.text.pdf.PdfWriter.getInstance(document, new java.io.FileOutputStream(file));
            document.open();

            com.lowagie.text.Font titleFont = new com.lowagie.text.Font(com.lowagie.text.Font.HELVETICA, 18, com.lowagie.text.Font.BOLD);
            com.lowagie.text.Paragraph title = new com.lowagie.text.Paragraph("Attendance Records", titleFont);
            title.setAlignment(com.lowagie.text.Element.ALIGN_CENTER);
            document.add(title);
            document.add(new com.lowagie.text.Paragraph("Generated on: " + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))));
            document.add(new com.lowagie.text.Paragraph(" "));

            // 9 columns with S.No as first column
            com.lowagie.text.pdf.PdfPTable pdfTable = new com.lowagie.text.pdf.PdfPTable(9);
            pdfTable.setWidthPercentage(100);
            pdfTable.setWidths(new float[]{8f, 20f, 16f, 12f, 18f, 12f, 10f, 10f, 12f});
            String[] headers = {"S.No", "Name", "Roll No", "Class", "Department", "Date", "Check-In", "Check-Out", "Status"};
            for (String h : headers) {
                com.lowagie.text.pdf.PdfPCell cell = new com.lowagie.text.pdf.PdfPCell(new com.lowagie.text.Phrase(h));
                cell.setBackgroundColor(new java.awt.Color(33, 150, 243));
                cell.setHorizontalAlignment(com.lowagie.text.Element.ALIGN_CENTER);
                cell.setVerticalAlignment(com.lowagie.text.Element.ALIGN_MIDDLE);
                cell.setPadding(5f);
                cell.setPhrase(new com.lowagie.text.Phrase(h, new com.lowagie.text.Font(com.lowagie.text.Font.HELVETICA, 12, com.lowagie.text.Font.BOLD, java.awt.Color.WHITE)));
                pdfTable.addCell(cell);
            }

            PreparedStatement ps = conn.prepareStatement(
                    "SELECT s.name, s.rollno, s.class, s.department, a.attendance_date, a.check_in_time, a.check_out_time, a.status " +
                            "FROM attendance a JOIN students s ON a.student_id = s.id ORDER BY a.attendance_date DESC, a.check_in_time DESC");
            ResultSet rs = ps.executeQuery();
            int i = 1;
            while (rs.next()) {
                String[] row = new String[]{
                        String.valueOf(i++),
                        rs.getString("name"),
                        rs.getString("rollno"),
                        rs.getString("class"),
                        rs.getString("department"),
                        String.valueOf(rs.getDate("attendance_date")),
                        String.valueOf(rs.getTime("check_in_time")),
                        String.valueOf(rs.getTime("check_out_time")),
                        rs.getString("status")
                };
                for (String val : row) {
                    com.lowagie.text.pdf.PdfPCell cell = new com.lowagie.text.pdf.PdfPCell(new com.lowagie.text.Phrase(val == null || val.equals("null") ? "" : val));
                    cell.setPadding(4f);
                    pdfTable.addCell(cell);
                }
            }
            document.add(pdfTable);
            document.close();
            JOptionPane.showMessageDialog(this, "PDF saved to: " + file.getAbsolutePath());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Failed to export PDF: " + ex.getMessage());
        }
    }

    // -------------------- New UI Panels --------------------    
    private JPanel createDashboardPanel() {
        // Create a custom panel with background image
        JPanel panel = new JPanel(new GridBagLayout()) {
            private Image backgroundImage;
            
            {
                try {
                    // Load a professional-looking background image (SVG)
                    String svgURI = new File("src/main/resources/about_slide1.jpg").toURI().toString();
                    backgroundImage = new ImageIcon(new java.net.URI(svgURI).toURL()).getImage();
                    if (backgroundImage == null) {
                        // Fallback to a gradient if image not found
                        setBackground(new Color(240, 245, 255));
                    }
                } catch (Exception e) {
                    // Fallback to a gradient if image loading fails
                    setBackground(new Color(240, 245, 255));
                    log("Error loading background image: " + e.getMessage());
                }
            }
            
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (backgroundImage != null) {
                    Graphics2D g2d = (Graphics2D) g.create();
                    g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g2d.drawImage(backgroundImage, 0, 0, getWidth(), getHeight(), this);
                    g2d.dispose();
                } else {
                    // Create a gradient background as fallback
                    Graphics2D g2d = (Graphics2D) g.create();
                    g2d.setPaint(new GradientPaint(0, 0, new Color(240, 245, 255), 
                                                  0, getHeight(), new Color(220, 230, 250)));
                    g2d.fillRect(0, 0, getWidth(), getHeight());
                    g2d.dispose();
                }
            }
        };
        
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 1.0;

        // University label at top with responsive font
        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 3; gbc.weighty = 0.1;
        gbc.insets = new Insets(50, 20, 10, 20);
        JLabel uniLabel = new JLabel("REVA University", JLabel.CENTER);
        uniLabel.setFont(new Font("Poppins", Font.BOLD, 54)); // Modern & elegant
        uniLabel.setForeground(new Color(255, 140, 0)); // REVA orange
        
        // Make font size responsive
        panel.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                int width = panel.getWidth();
                int fontSize = Math.max(28, Math.min(48, width / 28));
                uniLabel.setFont(new Font("Poppins", Font.BOLD, fontSize));
            }
        });
        panel.add(uniLabel, gbc);

        // Welcome header center with responsive font sizing
        gbc.gridx = 0; gbc.gridy = 1; gbc.weighty = 0.4;
        gbc.insets = new Insets(150, 60, 10, 10);
        JLabel welcomeLabel = new JLabel("Welcome to Face Attendance System", JLabel.CENTER);
        welcomeLabel.setFont(new Font("Poppins", Font.BOLD, 44));
        welcomeLabel.setBackground(new Color(0, 0, 0, 255)); // Black text for contrast
        welcomeLabel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        welcomeLabel.setForeground(Color.WHITE);

        // Responsive resizing
        panel.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                int width = panel.getWidth();
                int fontSize = Math.max(28, Math.min(44, width / 25));
                welcomeLabel.setFont(new Font("Poppins", Font.BOLD, fontSize));
            }
        });
        panel.add(welcomeLabel, gbc);

        // Stats panel at bottom with semi-transparent background
        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 3; gbc.weighty = 0.3;
        gbc.insets = new Insets(90, 20, 20, 20);
        JPanel statsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 10)) 
        {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2d = (Graphics2D) g.create();
                // Semi-transparent panel background for better readability
                g2d.setColor(new Color(0, 0, 0, 30));
                g2d.fillRoundRect(0, 0, getWidth(), getHeight(), 15, 15);
                g2d.dispose();
            }
        };
        statsPanel.setOpaque(false);
        
        // Create responsive stat labels with modern styling
        totalStudentsLabel = createResponsiveStatsLabel("Total Students: " + getTotalStudents());
        totalStudentsLabel.setForeground(Color.WHITE);
        statsPanel.add(totalStudentsLabel);
        
        todaysAttendanceLabel = createResponsiveStatsLabel("Today's Attendance: " + getTodaysAttendance());
        todaysAttendanceLabel.setForeground(Color.WHITE);
        statsPanel.add(todaysAttendanceLabel);
        
        totalRecordsLabel = createResponsiveStatsLabel("Total Records: " + getTotalAttendanceRecords());
        totalRecordsLabel.setForeground(Color.WHITE);
        statsPanel.add(totalRecordsLabel);

        panel.add(statsPanel, gbc);

        return panel;
    }
    
    private JPanel createStudentsPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(PANEL_COLOR);

        // Register button and refresh button
        JButton registerBtn = new JButton("Register New Student");
        registerBtn.setBackground(new Color(244, 67, 54));
        registerBtn.setFont(new Font("Segoe UI", Font.BOLD, 16));
        registerBtn.setBorder(BorderFactory.createEmptyBorder(8, 15, 8, 15));
        registerBtn.setForeground(Color.WHITE);
        registerBtn.setContentAreaFilled(false);
        registerBtn.setUI(new GradientButtonUI(new Color(76, 175, 80), new Color(56, 155, 60)));
        registerBtn.setOpaque(false);
        registerBtn.addActionListener(e -> {
            openRegisterDialog();
            // Refresh table after registration
            refreshStudentsTable();
        });

        JButton refreshBtn = new JButton("Refresh Table");
        refreshBtn.setBackground(new Color(244, 67, 54));
        refreshBtn.setFont(new Font("Segoe UI", Font.BOLD, 16));
        refreshBtn.setBorder(BorderFactory.createEmptyBorder(8, 15, 8, 15));
        refreshBtn.setForeground(Color.WHITE);
        refreshBtn.setContentAreaFilled(false);
        refreshBtn.setOpaque(false);
        refreshBtn.setUI(new GradientButtonUI(new Color(41, 98, 255), new Color(30, 80, 200)));
        refreshBtn.addActionListener(e -> {
            refreshStudentsTable();
            log("Students table manually refreshed.");
        });

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        buttonPanel.setBackground(PANEL_COLOR);
        buttonPanel.setBorder(BorderFactory.createEmptyBorder(20, 0, 0, 0)); // 20px down
        buttonPanel.add(registerBtn);
        buttonPanel.add(refreshBtn);
        panel.add(buttonPanel, BorderLayout.NORTH);

        // Students table
        studentsTable = createStudentsTable();
        JScrollPane scroll = new JScrollPane(studentsTable);
        panel.add(scroll, BorderLayout.CENTER);

        return panel;
    }

    private JPanel createAttendancePanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(PANEL_COLOR);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 20));
        buttonPanel.setBackground(PANEL_COLOR);
        buttonPanel.setBorder(BorderFactory.createEmptyBorder(200, 0, 0, 0));

       JButton checkInBtn = new JButton("Check-IN");
        checkInBtn.setBackground(new Color(244, 67, 54));
        checkInBtn.setFont(new Font("Segoe UI", Font.BOLD, 24));
        checkInBtn.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));
        checkInBtn.setForeground(Color.WHITE);
        checkInBtn.setContentAreaFilled(false);
        checkInBtn.setOpaque(false);
        checkInBtn.setUI(new GradientButtonUI(new Color(76, 175, 80), new Color(56, 155, 60)));
        checkInBtn.addActionListener(e -> openRecognitionDialog(false));

        JButton checkOutBtn = new JButton("Check-OUT");
        checkOutBtn.setBackground(new Color(244, 67, 54));
        checkOutBtn.setFont(new Font("Segoe UI", Font.BOLD, 24));
        checkOutBtn.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));
        checkOutBtn.setForeground(Color.WHITE);
        checkOutBtn.setContentAreaFilled(false);
        checkOutBtn.setOpaque(false);
        checkOutBtn.setUI(new GradientButtonUI(new Color(244, 67, 54), new Color(200, 40, 40)));
        checkOutBtn.addActionListener(e -> openRecognitionDialog(true));

        JButton viewBtn = new JButton("View Attendance Records");
        viewBtn.setBackground(new Color(244, 67, 54));
        viewBtn.setFont(new Font("Segoe UI", Font.BOLD, 24));
        viewBtn.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));
        viewBtn.setForeground(Color.WHITE);
        viewBtn.setContentAreaFilled(false);
        viewBtn.setOpaque(false);
        viewBtn.setUI(new GradientButtonUI(new Color(41, 98, 255), new Color(30, 80, 200)));
        viewBtn.addActionListener(e -> openViewAttendanceDialog());

        buttonPanel.add(checkInBtn);
        buttonPanel.add(checkOutBtn);
        buttonPanel.add(viewBtn);

        panel.add(buttonPanel, BorderLayout.NORTH);

        return panel;
    }

    private JPanel createAdminPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(PANEL_COLOR);

        // Title
        JLabel titleLabel = new JLabel("Administrator Dashboard", JLabel.CENTER);
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 24));
        titleLabel.setForeground(PRIMARY);
        titleLabel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        panel.add(titleLabel, BorderLayout.NORTH);

        // Admin functions panel
        JPanel functionsPanel = new JPanel(new GridLayout(3, 2, 20, 20));
        functionsPanel.setBackground(PANEL_COLOR);
        functionsPanel.setBorder(BorderFactory.createEmptyBorder(20, 40, 20, 40));

        // System Statistics
        JButton statsBtn = new JButton("System Statistics");
        statsBtn.setBackground(new Color(244, 67, 54));
        statsBtn.setForeground(Color.WHITE);
        statsBtn.setFont(new Font("Segoe UI", Font.BOLD, 20));
        statsBtn.setBorder(BorderFactory.createEmptyBorder(8, 15, 8, 15));
        statsBtn.setContentAreaFilled(false);
        statsBtn.setOpaque(false);
        statsBtn.setUI(new GradientButtonUI(new Color(76, 175, 80), new Color(76, 175, 80)));
        statsBtn.addActionListener(e -> showSystemStatistics());

        // Manage Students
        JButton manageStudentsBtn = new JButton("Manage Students");
        manageStudentsBtn.setBackground(new Color(244, 67, 54));
        manageStudentsBtn.setForeground(Color.WHITE);
        manageStudentsBtn.setFont(new Font("Segoe UI", Font.BOLD, 20));
        manageStudentsBtn.setBorder(BorderFactory.createEmptyBorder(8, 15, 8, 15));
        manageStudentsBtn.setContentAreaFilled(false);
        manageStudentsBtn.setOpaque(false);
        manageStudentsBtn.setUI(new GradientButtonUI(new Color(253, 0, 0), new Color(253, 0, 0)));
        manageStudentsBtn.addActionListener(e -> showStudentManagement());

        // View Audit Logs
        JButton auditBtn = new JButton("Audit Logs");
        auditBtn.setBackground(new Color(255, 165, 0));
        auditBtn.setForeground(Color.WHITE);
        auditBtn.setFont(new Font("Segoe UI", Font.BOLD, 20));
        auditBtn.setBorder(BorderFactory.createEmptyBorder(8, 15, 8, 15));
        auditBtn.setContentAreaFilled(false);
        auditBtn.setOpaque(false);
        auditBtn.setUI(new GradientButtonUI(new Color(255, 165, 0), new Color(255, 165, 0)));
        auditBtn.addActionListener(e -> showAuditLogs());

        // Database Backup
        JButton backupBtn = new JButton("Database Backup");
        backupBtn.setBackground(new Color(100, 200, 100));
        backupBtn.setForeground(Color.WHITE);
        backupBtn.setFont(new Font("Segoe UI", Font.BOLD, 20));
        backupBtn.setBorder(BorderFactory.createEmptyBorder(8, 15, 8, 15));
        backupBtn.setContentAreaFilled(false);
        backupBtn.setOpaque(false);
        backupBtn.setUI(new GradientButtonUI(new Color(41, 98, 255), new Color(41, 98, 255)));
        backupBtn.addActionListener(e -> performDatabaseBackup());

        // Model Management
        JButton modelBtn = new JButton("Model Management");
        modelBtn.setBackground(new Color(30, 40, 35));
        modelBtn.setForeground(Color.WHITE);
        modelBtn.setFont(new Font("Segoe UI", Font.BOLD, 20));
        modelBtn.setBorder(BorderFactory.createEmptyBorder(8, 15, 8, 15));
        modelBtn.setContentAreaFilled(false);
        modelBtn.setOpaque(false);
        modelBtn.setUI(new GradientButtonUI(new Color(30, 40, 35), new Color(30, 40, 35)));
        modelBtn.addActionListener(e -> showModelManagement());

        // System Settings
        JButton settingsBtn = new JButton("System Settings");
        settingsBtn.setBackground(new Color(240, 240, 240));
        settingsBtn.setForeground(Color.WHITE);
        settingsBtn.setFont(new Font("Segoe UI", Font.BOLD, 20));
        settingsBtn.setBorder(BorderFactory.createEmptyBorder(8, 15, 8, 15));
        settingsBtn.setContentAreaFilled(false);
        settingsBtn.setOpaque(false);
        settingsBtn.setUI(new GradientButtonUI(new Color(160, 160, 160), new Color(160, 160, 160)));
        settingsBtn.addActionListener(e -> showSystemSettings());

        functionsPanel.add(statsBtn);
        functionsPanel.add(manageStudentsBtn);
        functionsPanel.add(auditBtn);
        functionsPanel.add(backupBtn);
        functionsPanel.add(modelBtn);
        functionsPanel.add(settingsBtn);

        panel.add(functionsPanel, BorderLayout.CENTER);

        return panel;
    }

    private JButton createNavButton(String text) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("Segoe UI", Font.BOLD, 20));
        btn.setForeground(TEXT_LIGHT);
        btn.setBackground(PRIMARY);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setOpaque(true);
        btn.setPreferredSize(new Dimension(150, 40));
        btn.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseEntered(java.awt.event.MouseEvent evt) {
                if (btn.getBackground() != SECONDARY) {
                    btn.setBackground(PRIMARY.brighter());
                }
            }
            public void mouseExited(java.awt.event.MouseEvent evt) {
                if (btn.getBackground() != SECONDARY) {
                    btn.setBackground(PRIMARY);
                }
            }
        });
        btn.addActionListener(e -> {
            contentCards.show(contentPanel, text.toUpperCase());
            updateNavButtons(btn);

            // Add refresh functionality to dashboard and students buttons
            if (text.equals("Dashboard")) {
                refreshDashboardStats();
            } else if (text.equals("Students")) {
                refreshStudentsTable();
                log("Students table refreshed on navigation.");
            }
        });
        return btn;
    }

    private void updateNavButtons(JButton active) {
        JButton[] buttons = {dashboardBtn, studentsBtn, attendanceBtn, adminBtn};
        for (JButton btn : buttons) {
            if (btn == active) {
                btn.setBackground(SECONDARY);
            } else {
                btn.setBackground(PRIMARY);
            }
        }
    }

    private JTable createStudentsTable() {
        DefaultTableModel model = new DefaultTableModel(new String[]{"S.No", "Name", "Roll No", "Email", "Phone", "Class", "Department"}, 0);
        try {
            PreparedStatement ps = conn.prepareStatement("SELECT id, name, rollno, email, phone, class, department FROM students");
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                model.addRow(new Object[]{
                    rs.getInt("id"),
                    rs.getString("name"),
                    rs.getString("rollno"),
                    rs.getString("email"),
                    rs.getString("phone"),
                    rs.getString("class"),
                    rs.getString("department")
                });
            }
        } catch (Exception e) {
            log("Error loading students: " + e.getMessage());
        }
        JTable table = new JTable(model);
        table.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        table.getTableHeader().setFont(new Font("Segoe UI", Font.BOLD, 14));
        table.getTableHeader().setBackground(new Color(33, 150, 243));
        table.getTableHeader().setForeground(TEXT_LIGHT);
        table.getTableHeader().setResizingAllowed(false);
        table.getTableHeader().setReorderingAllowed(false);
        return table;
    }

    private void refreshStudentsTable() {
        if (studentsTable != null) {
            DefaultTableModel model = (DefaultTableModel) studentsTable.getModel();
            model.setRowCount(0); // Clear existing rows
            try {
                PreparedStatement ps = conn.prepareStatement("SELECT id, name, rollno, email, phone, class, department FROM students");
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    model.addRow(new Object[]{
                        rs.getInt("id"),
                        rs.getString("name"),
                        rs.getString("rollno"),
                        rs.getString("email"),
                        rs.getString("phone"),
                        rs.getString("class"),
                        rs.getString("department")
                    });
                }
            } catch (Exception e) {
                log("Error refreshing students: " + e.getMessage());
            }
        }
    }

    private String getTotalStudents() {
        try {
            PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM students");
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return String.valueOf(rs.getInt(1));
        } catch (Exception e) {}
        return "0";
    }

    private String getTodaysAttendance() {
        try {
            PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM attendance WHERE attendance_date = ?");
            ps.setDate(1, Date.valueOf(LocalDate.now()));
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return String.valueOf(rs.getInt(1));
        } catch (Exception e) {}
        return "0";
    }

    private String getTotalAttendanceRecords() {
        try {
            PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM attendance");
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return String.valueOf(rs.getInt(1));
        } catch (Exception e) {}
        return "0";
    }

    private void refreshDashboardStats() {
        SwingUtilities.invokeLater(() -> {
            if (totalStudentsLabel != null) {
                totalStudentsLabel.setText("Total Students: " + getTotalStudents());
            }
            if (todaysAttendanceLabel != null) {
                todaysAttendanceLabel.setText("Today's Attendance: " + getTodaysAttendance());
            }
            if (totalRecordsLabel != null) {
                totalRecordsLabel.setText("Total Records: " + getTotalAttendanceRecords());
            }
            log("Dashboard statistics refreshed.");
        });
    }

    // -------------------- Admin Dashboard Methods --------------------

    private void showSystemStatistics() {
        JDialog dialog = new JDialog(this, "System Statistics", true);
        dialog.setSize(500, 400);
        dialog.setLocationRelativeTo(this);

        GradientPanel panel = new GradientPanel(PRIMARY, PRIMARY.darker());
        panel.setLayout(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JLabel title = new JLabel("System Statistics", JLabel.CENTER);
        title.setFont(new Font("Segoe UI", Font.BOLD, 20));
        title.setForeground(Color.WHITE);
        panel.add(title, BorderLayout.NORTH);

        JTextArea statsArea = new JTextArea();
        statsArea.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        statsArea.setEditable(false);
        statsArea.setBackground(Color.WHITE);
        statsArea.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Get statistics
        StringBuilder stats = new StringBuilder();
        try {
            // Student count
            PreparedStatement ps1 = conn.prepareStatement("SELECT COUNT(*) FROM students");
            ResultSet rs1 = ps1.executeQuery();
            int studentCount = rs1.next() ? rs1.getInt(1) : 0;

            // Attendance count
            PreparedStatement ps2 = conn.prepareStatement("SELECT COUNT(*) FROM attendance");
            ResultSet rs2 = ps2.executeQuery();
            int attendanceCount = rs2.next() ? rs2.getInt(1) : 0;

            // Today's attendance
            PreparedStatement ps3 = conn.prepareStatement("SELECT COUNT(*) FROM attendance WHERE attendance_date = ?");
            ps3.setDate(1, Date.valueOf(LocalDate.now()));
            ResultSet rs3 = ps3.executeQuery();
            int todayCount = rs3.next() ? rs3.getInt(1) : 0;

            // Audit log count
            PreparedStatement ps4 = conn.prepareStatement("SELECT COUNT(*) FROM audit_logs");
            ResultSet rs4 = ps4.executeQuery();
            int auditCount = rs4.next() ? rs4.getInt(1) : 0;

            stats.append("System Statistics\n\n");
            stats.append("Total Students: ").append(studentCount).append("\n");
            stats.append("Total Attendance Records: ").append(attendanceCount).append("\n");
            stats.append("Today's Attendance: ").append(todayCount).append("\n");
            stats.append("Audit Log Entries: ").append(auditCount).append("\n\n");
            stats.append("System Status: Operational\n");
            stats.append("Last Updated: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        } catch (Exception ex) {
            stats.append("Error loading statistics: ").append(ex.getMessage());
        }

        statsArea.setText(stats.toString());
        JScrollPane scroll = new JScrollPane(statsArea);
        panel.add(scroll, BorderLayout.CENTER);

        JButton closeBtn = new JButton("Close");
        closeBtn.setBackground(new Color(244, 67, 54));
        closeBtn.setFont(new Font("Segoe UI", Font.BOLD, 16));
        closeBtn.setBorder(BorderFactory.createEmptyBorder(8, 15, 8, 15));
        closeBtn.setForeground(Color.WHITE);
        closeBtn.setContentAreaFilled(false);
        closeBtn.setOpaque(false);
        closeBtn.setUI(new GradientButtonUI(new Color(244, 67, 54), new Color(200, 40, 40)));
        closeBtn.addActionListener(e -> dialog.dispose());

        JPanel btnPanel = new JPanel();
        btnPanel.setOpaque(false);
        btnPanel.add(closeBtn);
        panel.add(btnPanel, BorderLayout.SOUTH);

        dialog.setContentPane(panel);
        dialog.setVisible(true);
    }

    private void showStudentManagement() {
        JOptionPane.showMessageDialog(this, "Student Management feature coming soon!\n\nThis will allow admins to:\n• View all students\n• Edit student information\n• Remove students\n• Reset face data", "Student Management", JOptionPane.INFORMATION_MESSAGE);
    }

    private void showAuditLogs() {
        JDialog dialog = new JDialog(this, "Audit Logs", true);
        dialog.setSize(800, 600);
        dialog.setLocationRelativeTo(this);

        GradientPanel panel = new GradientPanel(PRIMARY, PRIMARY.darker());
        panel.setLayout(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JLabel title = new JLabel("Audit Logs", JLabel.CENTER);
        title.setFont(new Font("Segoe UI", Font.BOLD, 20));
        title.setForeground(Color.WHITE);
        panel.add(title, BorderLayout.NORTH);

        JTextArea logArea = new JTextArea();
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        logArea.setEditable(false);
        logArea.setBackground(Color.WHITE);

        try {
            PreparedStatement ps = conn.prepareStatement(
                "SELECT timestamp, user_srn, action, details FROM audit_logs ORDER BY timestamp DESC LIMIT 100");
            ResultSet rs = ps.executeQuery();

            StringBuilder logs = new StringBuilder();
            logs.append("Recent Audit Logs (Last 100 entries):\n\n");
            logs.append(String.format("%-20s %-15s %-25s %s\n", "Timestamp", "User SRN", "Action", "Details"));
            logs.append("=".repeat(100)).append("\n");

            while (rs.next()) {
                logs.append(String.format("%-20s %-15s %-25s %s\n",
                    rs.getTimestamp("timestamp").toString().substring(0, 19),
                    rs.getString("user_srn") != null ? rs.getString("user_srn") : "ADMIN",
                    rs.getString("action"),
                    rs.getString("details") != null ? rs.getString("details") : ""));
            }

            logArea.setText(logs.toString());

        } catch (Exception ex) {
            logArea.setText("Error loading audit logs: " + ex.getMessage());
        }

        JScrollPane scroll = new JScrollPane(logArea);
        panel.add(scroll, BorderLayout.CENTER);

        JButton closeBtn = new JButton("Close");
        closeBtn.setBackground(new Color(244, 67, 54));
        closeBtn.setFont(new Font("Segoe UI", Font.BOLD, 16));
        closeBtn.setBorder(BorderFactory.createEmptyBorder(8, 15, 8, 15));
        closeBtn.setForeground(Color.WHITE);
        closeBtn.setContentAreaFilled(false);
        closeBtn.setOpaque(false);
        closeBtn.setUI(new GradientButtonUI(new Color(244, 67, 54), new Color(200, 40, 40)));
        closeBtn.addActionListener(e -> dialog.dispose());

        JPanel btnPanel = new JPanel();
        btnPanel.setOpaque(false);
        btnPanel.add(closeBtn);
        panel.add(btnPanel, BorderLayout.SOUTH);

        dialog.setContentPane(panel);
        dialog.setVisible(true);
    }

    private void performDatabaseBackup() {
        String[] options = {"Create Backup", "Restore from Backup", "Export to CSV", "Cancel"};
        int choice = JOptionPane.showOptionDialog(this, "Database Backup & Recovery Options",
                "Database Management", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, options, options[0]);

        switch (choice) {
            case 0: // Create Backup
                createDatabaseBackup();
                break;
            case 1: // Restore from Backup
                restoreDatabaseBackup();
                break;
            case 2: // Export to CSV
                exportDataToCSV();
                break;
            case 3: // Cancel
            default:
                break;
        }
    }

    private void createDatabaseBackup() {
        try {
            JFileChooser chooser = new JFileChooser();
            chooser.setSelectedFile(new File("face_attendance_backup_" +
                LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + ".sql"));
            int result = chooser.showSaveDialog(this);

            if (result == JFileChooser.APPROVE_OPTION) {
                File backupFile = chooser.getSelectedFile();

                // Create backup using mysqldump command
                String dbHost = "localhost";
                String dbPort = "3306";
                String dbName = "face_recognition_db";
                String dbUser = "root";
                String dbPass = "Root@12345";

                String command = String.format("mysqldump -h%s -P%s -u%s -p%s %s > \"%s\"",
                    dbHost, dbPort, dbUser, dbPass, dbName, backupFile.getAbsolutePath());

                Process process = Runtime.getRuntime().exec(new String[]{"cmd", "/c", command});
                int exitCode = process.waitFor();

                if (exitCode == 0) {
                    JOptionPane.showMessageDialog(this,
                        "Database backup created successfully!\nFile: " + backupFile.getName(),
                        "Backup Complete", JOptionPane.INFORMATION_MESSAGE);
                    auditLog(null, "admin", "Database Backup", "Backup file: " + backupFile.getName());
                } else {
                    JOptionPane.showMessageDialog(this,
                        "Failed to create database backup. Please check MySQL installation.",
                        "Backup Failed", JOptionPane.ERROR_MESSAGE);
                }
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                "Error creating backup: " + ex.getMessage(),
                "Backup Error", JOptionPane.ERROR_MESSAGE);
            log("Backup error: " + ex.getMessage());
        }
    }

    private void restoreDatabaseBackup() {
        try {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("SQL Files", "sql"));
            int result = chooser.showOpenDialog(this);

            if (result == JFileChooser.APPROVE_OPTION) {
                File backupFile = chooser.getSelectedFile();

                int confirm = JOptionPane.showConfirmDialog(this,
                    "WARNING: This will overwrite the current database!\n\n" +
                    "Are you sure you want to restore from: " + backupFile.getName() + "?",
                    "Confirm Database Restore", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

                if (confirm == JOptionPane.YES_OPTION) {
                    // Create restore using mysql command
                    String dbHost = "localhost";
                    String dbPort = "3306";
                    String dbUser = "root";
                    String dbPass = "Root@12345";
                    String dbName = "face_recognition_db";

                    String command = String.format("mysql -h%s -P%s -u%s -p%s %s < \"%s\"",
                        dbHost, dbPort, dbUser, dbPass, dbName, backupFile.getAbsolutePath());

                    Process process = Runtime.getRuntime().exec(new String[]{"cmd", "/c", command});
                    int exitCode = process.waitFor();

                    if (exitCode == 0) {
                        JOptionPane.showMessageDialog(this,
                            "Database restored successfully from: " + backupFile.getName() + "\n\nPlease restart the application.",
                            "Restore Complete", JOptionPane.INFORMATION_MESSAGE);
                        auditLog(null, "admin", "Database Restore", "Restored from: " + backupFile.getName());
                    } else {
                        JOptionPane.showMessageDialog(this,
                            "Failed to restore database. Please check the backup file and MySQL installation.",
                            "Restore Failed", JOptionPane.ERROR_MESSAGE);
                    }
                }
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                "Error restoring backup: " + ex.getMessage(),
                "Restore Error", JOptionPane.ERROR_MESSAGE);
            log("Restore error: " + ex.getMessage());
        }
    }

    private void exportDataToCSV() {
        try {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setSelectedFile(new File("face_attendance_export_" +
                LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))));
            int result = chooser.showSaveDialog(this);

            if (result == JFileChooser.APPROVE_OPTION) {
                File exportDir = chooser.getSelectedFile();
                if (!exportDir.exists()) {
                    exportDir.mkdirs();
                }

                // Export students table
                exportTableToCSV("students", new String[]{"id", "name", "rollno", "email", "phone", "class", "department", "face_id", "created_at"},
                    exportDir + "/students.csv");

                // Export attendance table
                exportTableToCSV("attendance", new String[]{"id", "student_id", "attendance_date", "check_in_time", "check_out_time", "status", "created_at"},
                    exportDir + "/attendance.csv");

                // Export audit_logs table
                exportTableToCSV("audit_logs", new String[]{"id", "user_srn", "admin_username", "action", "details", "ip_address", "timestamp"},
                    exportDir + "/audit_logs.csv");

                JOptionPane.showMessageDialog(this,
                    "Data exported successfully to: " + exportDir.getAbsolutePath() + "\n\nFiles created:\n• students.csv\n• attendance.csv\n• audit_logs.csv",
                    "Export Complete", JOptionPane.INFORMATION_MESSAGE);
                auditLog(null, "admin", "Data Export", "Exported to: " + exportDir.getName());
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                "Error exporting data: " + ex.getMessage(),
                "Export Error", JOptionPane.ERROR_MESSAGE);
            log("Export error: " + ex.getMessage());
        }
    }

    private void exportTableToCSV(String tableName, String[] columns, String filePath) throws Exception {
        try (java.io.FileWriter writer = new java.io.FileWriter(filePath);
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM " + tableName);
             ResultSet rs = ps.executeQuery()) {

            // Write header
            for (int i = 0; i < columns.length; i++) {
                if (i > 0) writer.write(",");
                writer.write("\"" + columns[i] + "\"");
            }
            writer.write("\n");

            // Write data
            while (rs.next()) {
                for (int i = 0; i < columns.length; i++) {
                    if (i > 0) writer.write(",");
                    String value = rs.getString(columns[i]);
                    writer.write("\"" + (value != null ? value.replace("\"", "\"\"") : "") + "\"");
                }
                writer.write("\n");
            }
        }
    }

    private void showModelManagement() {
        String[] options = {"Backup Model", "Restore Model", "Retrain Model", "Model Statistics", "Cancel"};
        int choice = JOptionPane.showOptionDialog(this, "AI Model Management Options",
                "Model Management", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, options, options[0]);

        switch (choice) {
            case 0: // Backup Model
                backupAIModel();
                break;
            case 1: // Restore Model
                restoreAIModel();
                break;
            case 2: // Retrain Model
                retrainAIModel();
                break;
            case 3: // Model Statistics
                showModelStatistics();
                break;
            case 4: // Cancel
            default:
                break;
        }
    }

    private void backupAIModel() {
        try {
            File modelFile = new File(this.modelFile);
            if (!modelFile.exists()) {
                JOptionPane.showMessageDialog(this, "No trained model found to backup.",
                    "No Model", JOptionPane.WARNING_MESSAGE);
                return;
            }

            JFileChooser chooser = new JFileChooser();
            chooser.setSelectedFile(new File("lbph_model_backup_" +
                LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".xml"));
            int result = chooser.showSaveDialog(this);

            if (result == JFileChooser.APPROVE_OPTION) {
                File backupFile = chooser.getSelectedFile();

                // Copy model file
                java.nio.file.Files.copy(modelFile.toPath(), backupFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                JOptionPane.showMessageDialog(this,
                    "AI Model backed up successfully!\nFile: " + backupFile.getName(),
                    "Backup Complete", JOptionPane.INFORMATION_MESSAGE);
                auditLog(null, "admin", "Model Backup", "Backup file: " + backupFile.getName());
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                "Error backing up model: " + ex.getMessage(),
                "Backup Error", JOptionPane.ERROR_MESSAGE);
            log("Model backup error: " + ex.getMessage());
        }
    }

    private void restoreAIModel() {
        try {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("XML Model Files", "xml"));
            int result = chooser.showOpenDialog(this);

            if (result == JFileChooser.APPROVE_OPTION) {
                File backupFile = chooser.getSelectedFile();

                int confirm = JOptionPane.showConfirmDialog(this,
                    "This will replace the current AI model!\n\n" +
                    "Are you sure you want to restore from: " + backupFile.getName() + "?",
                    "Confirm Model Restore", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

                if (confirm == JOptionPane.YES_OPTION) {
                    // Backup current model first
                    File currentModel = new File(modelFile);
                    if (currentModel.exists()) {
                        File autoBackup = new File(modelFile + ".autobackup");
                        java.nio.file.Files.copy(currentModel.toPath(), autoBackup.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }

                    // Restore from backup
                    java.nio.file.Files.copy(backupFile.toPath(), currentModel.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                    JOptionPane.showMessageDialog(this,
                        "AI Model restored successfully from: " + backupFile.getName(),
                        "Restore Complete", JOptionPane.INFORMATION_MESSAGE);
                    auditLog(null, "admin", "Model Restore", "Restored from: " + backupFile.getName());
                }
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                "Error restoring model: " + ex.getMessage(),
                "Restore Error", JOptionPane.ERROR_MESSAGE);
            log("Model restore error: " + ex.getMessage());
        }
    }

    private void retrainAIModel() {
        int confirm = JOptionPane.showConfirmDialog(this,
            "This will retrain the AI model using all current face data.\n\n" +
            "The process may take several minutes. Continue?",
            "Confirm Model Retraining", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);

        if (confirm == JOptionPane.YES_OPTION) {
            // Run training in background thread
            new Thread(() -> {
                try {
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(this, "Model retraining started in background.\nCheck logs for progress.",
                            "Retraining Started", JOptionPane.INFORMATION_MESSAGE);
                    });

                    auditLog(null, "admin", "Model Retraining", "Started manual retraining");

                    // Backup current model before retraining
                    File currentModel = new File(modelFile);
                    if (currentModel.exists()) {
                        File backupBeforeRetrain = new File(modelFile + ".pre_retrain_backup");
                        java.nio.file.Files.copy(currentModel.toPath(), backupBeforeRetrain.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }

                    // Perform retraining
                    trainModel();

                    auditLog(null, "admin", "Model Retraining", "Completed successfully");

                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(this, "Model retraining completed successfully!",
                            "Retraining Complete", JOptionPane.INFORMATION_MESSAGE);
                    });

                } catch (Exception ex) {
                    log("Model retraining error: " + ex.getMessage());
                    auditLog(null, "admin", "Model Retraining", "Failed: " + ex.getMessage());

                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(this, "Model retraining failed: " + ex.getMessage(),
                            "Retraining Failed", JOptionPane.ERROR_MESSAGE);
                    });
                }
            }).start();
        }
    }

    private void showModelStatistics() {
        JOptionPane.showMessageDialog(this, "Model Statistics feature coming soon!\n\nThis will show:\n• Model accuracy metrics\n• Training data statistics\n• Performance benchmarks\n• Model version information", "Model Statistics", JOptionPane.INFORMATION_MESSAGE);
    }

    private void showSystemSettings() {
        JOptionPane.showMessageDialog(this, "System Settings feature coming soon!\n\nThis will allow admins to:\n• Configure system parameters\n• Manage user permissions\n• Set up notifications\n• Configure database settings", "System Settings", JOptionPane.INFORMATION_MESSAGE);
    }

    private JPanel createFooterPanel() {
        JPanel footerPanel = new JPanel(new BorderLayout());
        footerPanel.setBackground(Color.decode("#2C3E50"));
        footerPanel.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));

        // North: University info
        JPanel uniPanel = new JPanel();
        uniPanel.setOpaque(false);
        uniPanel.setLayout(new BoxLayout(uniPanel, BoxLayout.Y_AXIS));

        JLabel uniLabel = new JLabel("REVA University");
        uniLabel.setFont(new Font("Segoe UI", Font.BOLD, 20));
        uniLabel.setForeground(Color.WHITE);
        uniLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        uniPanel.add(uniLabel);

        JLabel addrLabel = new JLabel("Rukmini Knowledge Park, Kattigenahalli, Yelahanka, Bangalore, Karnataka, India, 560 064");
        addrLabel.setFont(new Font("Segoe UI", Font.PLAIN, 16));
        addrLabel.setForeground(Color.WHITE);
        addrLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        uniPanel.add(addrLabel);

        footerPanel.add(uniPanel, BorderLayout.NORTH);

        // South: Contact and Follow Us side by side
        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.setOpaque(false);

        // West: Follow Us (with 50px right margin)
        JPanel followPanel = new JPanel();
        followPanel.setOpaque(false);
        followPanel.setLayout(new BoxLayout(followPanel, BoxLayout.Y_AXIS));
        followPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 0, 50)); // 50px right

        JLabel followLabel = new JLabel("Follow Us");
        followLabel.setFont(new Font("Segoe UI", Font.BOLD, 16));
        followLabel.setForeground(Color.WHITE);
        followLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        followPanel.add(followLabel);

        // Social media links
        JPanel socialPanel = new JPanel();
        socialPanel.setOpaque(false);
        socialPanel.setLayout(new FlowLayout(FlowLayout.LEFT, 20, 5));
        socialPanel.setBorder(BorderFactory.createEmptyBorder(0, 60, 0, 0));

        String[] socialNames = {"Facebook", "Twitter", "YouTube", "Instagram", "LinkedIn"};
        String[] socialUrls = {
            "https://www.facebook.com/REVAUniversity",
            "https://twitter.com/REVAUniversity",
            "https://www.youtube.com/@REVAUniversity",
            "https://www.instagram.com/revauniversity/",
            "https://www.linkedin.com/school/reva-university/"
        };

        for (int i = 0; i < socialNames.length; i++) {
            JLabel socialLabel = new JLabel(socialNames[i]);
            socialLabel.setFont(new Font("Segoe UI", Font.PLAIN, 14));
            socialLabel.setForeground(Color.decode("#3498DB"));
            socialLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            final String url = socialUrls[i];
            socialLabel.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    try {
                        Desktop.getDesktop().browse(new URI(url));
                    } catch (Exception ex) {
                        log("Error opening link: " + ex.getMessage());
                    }
                }
            });
            socialPanel.add(socialLabel);
        }

        followPanel.add(socialPanel);

        bottomPanel.add(followPanel, BorderLayout.WEST);

        // East: Contact (with 50px left margin)
        JPanel contactPanel = new JPanel();
        contactPanel.setOpaque(false);
        contactPanel.setLayout(new BoxLayout(contactPanel, BoxLayout.Y_AXIS));
        contactPanel.setBorder(BorderFactory.createEmptyBorder(10, 50, 0, 150)); // 50px left

        JLabel contactLabel = new JLabel("Contact");
        contactLabel.setFont(new Font("Segoe UI", Font.BOLD, 16));
        contactLabel.setForeground(Color.WHITE);
        contactLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        contactPanel.add(contactLabel);

        JLabel phone1 = new JLabel("+91-90211 90211");
        phone1.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        phone1.setForeground(Color.WHITE);
        phone1.setAlignmentX(Component.LEFT_ALIGNMENT);
        contactPanel.add(phone1);

        JLabel phone2 = new JLabel("+91-80-46966966");
        phone2.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        phone2.setForeground(Color.WHITE);
        phone2.setAlignmentX(Component.LEFT_ALIGNMENT);
        contactPanel.add(phone2);

        bottomPanel.add(contactPanel, BorderLayout.EAST);

        footerPanel.add(bottomPanel, BorderLayout.SOUTH);

        return footerPanel;
    }

    // Helper method to create responsive stats labels with consistent styling
    private JLabel createResponsiveStatsLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(new Font("Segoe UI", Font.BOLD, 18));
        // Use white text for better visibility on semi-transparent background
        label.setForeground(Color.WHITE);
        // Add a subtle shadow effect for better readability
        label.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        
        // Make the font size responsive to container resizing
        label.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                Container parent = label.getParent();
                if (parent != null) {
                    int width = parent.getWidth();
                    // Dynamically adjust font size based on container width
                    int fontSize = Math.max(14, Math.min(18, width / 60));
                    label.setFont(new Font("Segoe UI", Font.BOLD, fontSize));
                }
            }
        });
        
        return label;
    }

}