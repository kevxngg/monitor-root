# El servicio para ROMs se arranca con app_process por su nombre: no se puede renombrar.
-keep class com.kevxngg.monitorroot.helper.HelperMain {
    public static void main(java.lang.String[]);
}
