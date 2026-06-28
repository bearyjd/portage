# Consumer R8 rules for :adb-bridge. These travel only with this module, so they apply to the
# degoogle (Tier-1) receiver — the sole artifact that links the bridge — and never reach the
# bridge-free play / sender variants (ADR-003 flavor split).
#
# libadb-android bundles Conscrypt for the localhost TLS connect to adbd. Conscrypt ships
# platform-adapter shims (KitKat / pre-KitKat) that reference SSL internals which do not exist at
# R8 time on modern Android; they are optional fallbacks that are never reached on minSdk 31. R8
# full mode treats these dangling references as errors, so suppress them to let the degoogle
# receiver minify. This is a missing-class suppression only — it keeps nothing.
-dontwarn com.android.org.conscrypt.SSLParametersImpl
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl
