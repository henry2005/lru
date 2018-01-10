

/**
 * @author quyan
 * @version 1.0
 * @date 2018/1/9
 * @company lzh
 * @category com.lzhplus.app.activity
 * @copyright copyright(c) 2015~2016
 */
public interface ValueLoader<K, V> {

    V get(K key);
}
