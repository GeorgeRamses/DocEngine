# 1. Keep main app safe
# OR, to be safe, match MainKt in ANY package:
-keep class **.MainKt { *; }
-keep class com.example.autorename.** { *; }

# 2. Apache Commons Logging (CRITICAL FOR PDFBOX)
# Keep the logging classes so PDFBox doesn't crash at runtime
-keep class org.apache.commons.logging.** { *; }
-keep class org.apache.commons.logging.impl.** { *; }

# Ignore warnings about missing optional backends (Log4j, Avalon, etc.)
# We keep the core logger, but ignore the specific adapters we don't use.
-dontwarn org.apache.commons.logging.impl.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.avalon.framework.logger.**
-dontwarn org.apache.log.**
-dontwarn org.jboss.logging.**
-dontwarn org.jboss.logmanager.**
-dontwarn org.jboss.vfs.**
-dontwarn org.slf4j.**

# 3. Ignore Web/Server Classes
-dontwarn javax.servlet.**

# 4. Ignore Crypto (BouncyCastle)
-dontwarn org.bouncycastle.**
-dontwarn org.apache.pdfbox.pdmodel.encryption.**
-dontwarn org.apache.pdfbox.filter.**

# 5. Ignore Internal/Desktop specifics
#-dontwarn com.sun.jna.**
-dontwarn sun.misc.Cleaner
-dontwarn sun.java2d.**
-dontwarn com.apple.eawt.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn javax.xml.bind.**

# 6. Ignore Apache Commons
-dontwarn org.apache.commons.io.**
-dontwarn com.github.jaiimageio.**
# ... (Keep all your existing rules) ...

# ==========================================
# FIX: KEEP JNA (Prevents UnsatisfiedLinkError)
# ==========================================
# JNA uses native DLLs that need specific method names.
# We must prevent ProGuard from renaming anything in JNA.
-keep class com.sun.jna.** { *; }
-keep class net.sourceforge.tess4j.** { *; }

# ==========================================
# FIX: KEEP JAVACV / OPENCV / BYTEDECO
# ==========================================
# JavaCV uses JNI + native presets; ProGuard must not touch them.
-keep class org.bytedeco.** { *; }
-dontwarn org.bytedeco.**

# OpenCV Android module is pulled in by javacv-platform but is
# unused on desktop — suppress all its Android class references.
-dontwarn org.opencv.android.**
-dontwarn org.opencv.core.**
-dontwarn android.**

# Lept4j / NativeSize — required by Tess4j native library loading.
# Must be KEPT (not just -dontwarn) so ProGuard doesn't strip them.
-keep class net.sourceforge.lept4j.** { *; }
-dontwarn net.sourceforge.lept4j.**
-dontwarn com.ochafik.lang.jnaerator.runtime.**

# PDFBox debugger — optional GUI tool, not used at runtime.
-dontwarn org.apache.pdfbox.debugger.**

# ==========================================
# FIX: KEEP NATIVE RESOURCE DIRECTORIES
# ==========================================
# Tess4j's LoadLibs.extractNativeResources() calls
# classLoader.getResources("win32-x86-64") to find native DLLs
# bundled inside JARs.  ProGuard strips directory entries by
# default, which makes that enumeration return nothing → crash.
-keepdirectories win32-x86-64
-keepdirectories win32-x86-64/**
-keepdirectories linux-x86-64
-keepdirectories linux-x86-64/**
-keepdirectories darwin
-keepdirectories darwin/**

# ==========================================
# FIX: KEEP IMAGEIO SPI (Prevents the Crash)
# ==========================================
# Keep the specific library causing your crash
-keep class com.github.jaiimageio.** { *; }

# Keep all Java ImageIO Service Providers (Readers/Writers)
-keep class * extends javax.imageio.spi.IIOServiceProvider { *; }
-keep class * extends javax.imageio.spi.ImageReaderSpi { *; }
-keep class * extends javax.imageio.spi.ImageWriterSpi { *; }
-keep class * extends javax.imageio.spi.ImageInputStreamSpi { *; }
-keep class * extends javax.imageio.spi.ImageOutputStreamSpi { *; }

# ==========================================
# FIX: KEEP SLF4J PROVIDERS (Fixes Logging)
# ==========================================
-keep class org.slf4j.** { *; }
-keep class org.slf4j.simple.** { *; }
-keep class org.slf4j.impl.** { *; }

# Keep Service Loader configurations in META-INF
-keepdirectories META-INF/**
