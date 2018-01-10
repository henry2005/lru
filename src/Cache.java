

import java.lang.Exception;

/**
 * @author quyan
 * @version 1.0
 * @date 2018/1/9
 * @company lzh
 * @category com.lzhplus.app.activity
 * @copyright copyright(c) 2015~2016
 */
public interface Cache<K, V> {

    /**
     * 统计
     */
    interface Stat {
        int incrGet();

        int incrHits();

        int incrLRU();

        int incrCleanup();

        int incrSet();

        void print();
    }

    /**
     * 查询缓存
     * @param key
     * @return
     * @throws Exception
     */
    V get(K key) throws Exception;

    /**
     * 加入缓存
     * @param key
     * @param value
     */
    void set(K key, V value);

    /**
     * 值加载器
     * @return
     */
    ValueLoader<K, V> valueLoader();

    /**
     * 当前缓存size
     * @return
     */
    int size();

    /**
     * 统计句柄
     * @return
     */
    Stat stat();
}
