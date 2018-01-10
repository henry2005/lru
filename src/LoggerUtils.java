/**
 * @author quyan
 * @version 1.0
 * @date 2018/1/9
 * @company lzh
 * @category com.lzhplus.app.activity
 * @copyright copyright(c) 2015~2016
 */
public class LoggerUtils {
    
    public static void log(String content, Object... param) {
        System.out.printf(content, param);
        System.out.println();
    }

    public static void debug(String content, Object... param) {
//        log(content, param);
    }

    public static void info(String content, Object... param) {
//        log(content, param);
    }

    public static void error(String content, Object... param) {
        log(content, param);
    }
}
