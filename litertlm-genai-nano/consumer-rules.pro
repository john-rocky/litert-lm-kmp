# ML Kit names its component registrar only in its manifest, as a string, and creates it by reflection. R8's full mode
# otherwise removes the constructor, ML Kit then starts with no components, and every Gemini Nano call in a release
# build fails. ML Kit's own rules do not keep it (com.google.mlkit:common:18.11.0).
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }

# litertlm-genai finds the adapter by this name, so that it needs nothing of this artifact or of ML Kit.
-keep class io.github.johnrocky.litertlm.genai.nano.MlKitNanoAdapter { <init>(); }
