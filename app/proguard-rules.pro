# JSch instantiates ciphers, KEX, MACs and signatures by class name from its config table.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn org.slf4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.ietf.jgss.**
-dontwarn org.newsclub.net.unix.**
-dontwarn com.sun.jna.**
-dontwarn org.bouncycastle.**

# sherpa-onnx JNI reads Kotlin config objects field by field and builds result objects from native code.
-keep class com.k2fsa.sherpa.onnx.** { *; }
