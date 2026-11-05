# litertlm-android ships no proguard.txt, and its JNI reaches Kotlin by name: FindClass + NewObject on
# LiteRtLmJniException, InputData and its subclasses, BenchmarkInfo, EmbeddingResponse, and GetMethodID
# on the callback object handed to nativeSendMessageAsync. A rename or removal aborts the process with a
# JNI error, which nothing in the app can catch, so the runtime package stays as it is.
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclasseswithmembernames class com.google.ai.edge.litertlm.** { native <methods>; }
