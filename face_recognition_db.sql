-- =====================================================
-- FACE ATTENDANCE SYSTEM - DATABASE CREATION ONLY
-- =====================================================

-- Create database if it doesn't exist
CREATE DATABASE IF NOT EXISTS face_recognition_db;
USE face_recognition_db;

-- =====================================================
-- 1. STUDENTS TABLE
-- =====================================================
CREATE TABLE IF NOT EXISTS students (
    id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    rollno VARCHAR(20) NOT NULL UNIQUE COMMENT 'SRN format: SRN21CS001',
    email VARCHAR(100) NOT NULL UNIQUE COMMENT 'Must be @gmail.com',
    phone VARCHAR(15) NOT NULL UNIQUE COMMENT '10-digit phone number',
    class VARCHAR(50) NOT NULL,
    department VARCHAR(100) NOT NULL,
    face_id VARCHAR(50) NOT NULL UNIQUE COMMENT 'Unique face identifier',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT chk_valid_email CHECK (email LIKE '%@gmail.com'),
    CONSTRAINT chk_phone_format CHECK (phone REGEXP '^[0-9]{10}$'),
    CONSTRAINT chk_rollno_format CHECK (rollno REGEXP '^SRN[0-9]{2}[A-Z]{2}[0-9]{3}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =====================================================
-- 2. FACE DATA TABLE
-- =====================================================
CREATE TABLE IF NOT EXISTS face_data (
    id INT AUTO_INCREMENT PRIMARY KEY,
    student_id INT NOT NULL,
    image_path VARCHAR(255) NOT NULL COMMENT 'Path to face image file',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    FOREIGN KEY (student_id) REFERENCES students(id) ON DELETE CASCADE,
    INDEX idx_student_id (student_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =====================================================
-- 3. ATTENDANCE TABLE
-- =====================================================
CREATE TABLE IF NOT EXISTS attendance (
    id INT AUTO_INCREMENT PRIMARY KEY,
    student_id INT NOT NULL,
    attendance_date DATE NOT NULL,
    check_in_time TIME NOT NULL COMMENT 'Time when student checked in',
    check_out_time TIME NULL COMMENT 'Time when student checked out',
    status ENUM('Present', 'Late', 'Absent') DEFAULT 'Present',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    FOREIGN KEY (student_id) REFERENCES students(id) ON DELETE CASCADE,
    UNIQUE KEY unique_attendance (student_id, attendance_date),
    
    CONSTRAINT chk_checkout_after_checkin CHECK (
        check_out_time IS NULL OR check_out_time > check_in_time
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =====================================================
-- 4. AUDIT LOGS TABLE
-- =====================================================
CREATE TABLE IF NOT EXISTS audit_logs (
    id INT AUTO_INCREMENT PRIMARY KEY,
    user_srn VARCHAR(20) NULL COMMENT 'Student SRN for student operations',
    admin_username VARCHAR(50) NULL COMMENT 'Admin username for admin operations',
    action VARCHAR(255) NOT NULL COMMENT 'Action performed',
    details TEXT NULL COMMENT 'Additional details about the action',
    ip_address VARCHAR(45) NULL COMMENT 'IP address of the user',
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    FOREIGN KEY (user_srn) REFERENCES students(rollno) ON DELETE SET NULL,
    INDEX idx_timestamp (timestamp),
    INDEX idx_user_srn (user_srn),
    INDEX idx_admin_username (admin_username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =====================================================
-- 5. ADMINS TABLE
-- =====================================================
CREATE TABLE IF NOT EXISTS admins (
    id INT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL COMMENT 'SHA-256 hashed password',
    email VARCHAR(100) NOT NULL UNIQUE,
    role ENUM('admin', 'faculty') DEFAULT 'admin',
    last_login TIMESTAMP NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT chk_admin_email CHECK (email LIKE '%@%.%')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;