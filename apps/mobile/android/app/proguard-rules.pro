# ConnectX Proguard Rules
-keepattributes *Annotation*
-keepclassmembers class * {
    @org.webkit.regexp.* <methods>;
}
