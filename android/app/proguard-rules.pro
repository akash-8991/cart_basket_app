# kotlinx.serialization keeps generated serializers via its own consumer rules.
# Strip verbose logging in release.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
