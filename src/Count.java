

import java.lang.Exception;

/**
 * @author quyan
 * @version 1.0
 * @date 2018/1/9
 * @company lzh
 * @category com.lzhplus.app.activity
 * @copyright copyright(c) 2015~2016
 */
public interface Count<K, V> extends Cache<K, V> {
    /**
     *
     * @param key
     * @return
     * @throws Exception
     */
    V get(K key, Cache<K, V> cache) throws Exception;
}
