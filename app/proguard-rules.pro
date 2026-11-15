# The ROM service is started by name with app_process: it must not be renamed.
-keep class com.kevxngg.monitorroot.helper.HelperMain {
    public static void main(java.lang.String[]);
}
