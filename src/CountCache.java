

import java.lang.Exception;import java.lang.Override;import java.lang.UnsupportedOperationException;import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author quyan
 * @version 1.0
 * @date 2018/1/9
 * @company lzh
 * @category com.lzhplus.app.activity
 * @copyright copyright(c) 2015~2016
 */
public class CountCache<K, V> extends LRUCache<K, V> implements Count<K, V> {
    private int count = 3;

    public CountCache(LRUCacheBuilder<K, V> builder) {
        super(builder, null, false);
    }

    public void setCount(int count) {
        this.count = count;
    }

    @Override
    public V get(K key) throws Exception {
        throw new UnsupportedOperationException();
    }

    class CountEntry<K, V> extends Entry<K, V> {
        AtomicInteger atomicInteger = new AtomicInteger(0);

        CountEntry(V value, K key) {
            super(value, key);
        }

        int incr() {
            // 热点数据期内
            if (now() - time < expireMillis) {
                int incr = atomicInteger.incrementAndGet();
                LoggerUtils.debug("%s:计数器加1:%s", key, incr);
                return incr;
            } else {
                time = now();
                atomicInteger.getAndSet(1);
                LoggerUtils.debug("%s:刷新计数器:1", key);
                return atomicInteger.get();
            }
        }
    }

    @Override
    public V get(K key, Cache<K, V> cache) throws Exception {
        CountEntry<K, V> entry = (CountEntry<K, V>) _set(key, null);

        V val = cache.valueLoader().get(key);
        entry.value = val;


        boolean offered = false;
        // 可升级为热点数据
        if (entry.incr() >= count) {
            LoggerUtils.debug("升级为热点数据 : %s", entry.key);
            cache.set(key, val);
            Entry v = localCache.remove(key);
            if (v != null) {
                // 删除引用
                offered = offerEntry(v);

                // 数据有变更
                if (offered) {
                    purge(v.node);
                }
            }
        }

        return entry.value;
    }

    @Override
    protected boolean offerEntry(Entry entry) {
        // LRU最近使用 != 当前元素
        if (entry != tail.entry) {
            LoggerUtils.debug("删除LRU队列中Node的Entry引用:%s", entry.key);
            Node c = entry.node;
            // 创建新Node
            Node tmp = new Node(entry);
            for (;;) {
                // Entry的Node调整
                if (compareAndSetNode(entry, c, tmp)) {
                    // 原Node引用删除
                    if (c != null) {
                        c.entry = null;
                    }
                    break;
                }
            }
        } else {
            return false;
        }

        return true;
    }

    @Override
    protected Entry<K, V> newEntry(V val, K key) {
        return new CountEntry(val, key);
    }
}
