Face Attendance (Java + Swing + JavaCV + MySQL/SQLite)

This module contains the main desktop application: com.faceattendance.FaceAttendanceApp. It uses JavaCV/OpenCV for camera and face recognition (LBPH), Swing for UI, and MySQL for persistence with SQLite fallback support.

Quick Start (Windows)
- Prerequisites:
  - Java 11+ (JDK). Verify: java -version
  - Maven 3.8+ (if building from source). Verify: mvn -v
  - MySQL Server running locally on port 3306 with a user that can create databases. Default in code: user=root, password=1234
  - Microsoft Visual C++ 2015–2022 x64 Redistributable (required for OpenCV native libs). Installer is provided one level up as vc_redist.x64.exe
  - A working webcam

- Files that must exist in the working directory when you run:
  - haarcascade_frontalface_alt.xml (already included at project root FaceAttendance/FaceAttendance)
  - models/ and dataset/ directories will be created automatically if missing

Run options
1) Easiest: run.bat
   - Double-click FaceAttendance/run.bat from Windows Explorer
   - It changes into the module folder and runs: mvn exec:java
   - The app window opens. Keep the terminal open while running.

2) Maven from terminal
   - cd "FaceAttendance/FaceAttendance"
   - mvn -q exec:java

3) Build a jar (optional)
   - mvn -q -DskipTests package
   - Then run the jar with dependencies if you configure a shaded jar; otherwise prefer exec:java.

First-time setup inside the app
- The app will connect to MySQL and automatically:
  - Create database face_recognition_db if it does not exist
  - Create tables students, face_data, attendance
- If MySQL connection fails, the application will automatically fall back to SQLite:
  - A local SQLite database file (face_recognition.db) will be created in the working directory
  - No configuration changes needed - this happens automatically
  - If you still want to use MySQL, edit DB credentials in:
    - File: src/main/java/com/faceattendance/FaceAttendanceApp.java
    - Method: connectDB()
    - Line contains: DriverManager.getConnection("jdbc:mysql://localhost:3306/face_recognition_db", "root", "1234");

Using the app
- Dashboard: Shows real-time statistics that automatically refresh:
  - When the application starts
  - When you click the Dashboard button
  - After marking attendance or logout
- Register Student: enter Name, Roll No, Email, Class, Department. Click Capture Face.
  - The camera opens and captures 25 samples automatically when a face is detected.
  - Each image is saved to dataset/{studentId}/ and recorded in the database.
  - When 25 images are collected, training starts automatically in background and model is saved to models/lbph_model.xml
- Students: View and manage student records. The table automatically refreshes:
  - When you click the Students button
  - After registering a new student
- Train Model: You can also press this to retrain from all saved samples.
- Mark Attendance: Starts camera, recognizes known faces, and inserts a record into attendance (once per day per student).
- View Attendance: Opens a table of attendance records.

Troubleshooting
- OpenCV native load failed / UnsatisfiedLinkError:
  - Install VC++ Redistributable (vc_redist.x64.exe) and restart.
  - Make sure you run on 64-bit Java.
- Cannot read model / Model not found:
  - You must train at least once. Either complete an enrollment (25 shots) or click Train Model after collecting images.
- Camera not opening or in use:
  - Close other apps using the webcam.
  - If you have multiple cameras, you may need to change the camera index in code (OpenCVFrameGrabber(0) -> (1)).
- Cascade not found:
  - Ensure haarcascade_frontalface_alt.xml is in the working directory you run from. The run.bat already runs from FaceAttendance/FaceAttendance so the file is found.
- MySQL access denied or not running:
  - Start the MySQL service and verify credentials. Update connectDB() accordingly and rebuild.

Alternate sample (SQLite)
- There is a simple demo at FaceAttendance/src/FaceAttendance.java that uses SQLite for a basic attendance form (no camera).
- To run it quickly with Java 11 without Maven:
  - cd "FaceAttendance"
  - javac src/FaceAttendance.java
  - java -cp src FaceAttendance

Notes
- Dataset paths are stored normalized with forward slashes so OpenCV can read them on Windows.
- LBPH threshold is set to 75.0 in the code; adjust if needed for your data.

FAQ
- Where are images saved?
  - Under dataset/{studentId}/ inside the module directory.
- Where is the model saved?
  - models/lbph_model.xml
- Where is the DB?
  - MySQL database face_recognition_db on localhost. A lightweight SQLite example also exists separately (see above).
