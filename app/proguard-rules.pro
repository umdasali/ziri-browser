# Tor's native library calls back into these classes by name.
-keep class org.torproject.jni.** { *; }
-keep class net.freehaven.tor.control.** { *; }
# Room, Compose and OkHttp ship their own consumer rules.
