public class ClassLoadDemo {
    
    public static void main(String[] args) {
        ClassLoader classLoader1 = ClassLoadDemo.class.getClassLoader();
        ClassLoader classLoader2 = classLoader1.getParent();         
        ClassLoader classLoader3 = classLoader2.getParent();
        System.out.println(classLoader1);
        System.out.println(classLoader2);
        System.out.println(classLoader3); 
        // output
        // sun.misc.Launcher$AppClassLoader@73d16e93
        // sun.misc.Launcher$ExtClassLoader@15db9742
        // null
    }
}