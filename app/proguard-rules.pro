# OpenCV Java bindings are called from native code via JNI
-keep class org.opencv.** { *; }

# TensorFlow Lite and the Flex delegate are looked up by name from native code
-keep class org.tensorflow.lite.** { *; }
