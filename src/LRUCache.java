import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author quyan
 * @version 1.0
 * @date 2018/1/8
 * @company lzh
 * @category com.lzhplus.app.activity
 * @copyright copyright(c) 2015~2016
 */
public class LRUCache<K, V> implements Cache<K, V> {
    /**
     * K-V 缓存
     */
    protected Map<K, Entry<K, V>> localCache;

    /**
     * 加热器
     */
    private Count<K, V> countLruCache;

    /**
     * 空Node队列
     * Entry被访问后，新增Node放置LRU尾部，原Node删除Entry引用，加入空Node队列
     */
    private Queue<Node> queue = new ConcurrentLinkedQueue<Node>();
    /**
     * 空Node队列计数器
     */
    private AtomicInteger queueCount = new AtomicInteger(0);

    /**
     * 统计器
     */
    private Stat stat = new Stat2Imp();

    /**
     * 最大容量
     */
    private int capacity = 32; // default capacity
    /**
     * 高水位容量
     */
    private int hwmCapacity = 0; // high water mark
    /**
     * 超时时间
     */
    protected long expireMillis = 500;
    /**
     * 空Node队列容量因子
     */
    private int cleanUpFactor = 3;
    /**
     * 空Node队列容量
     */
    private int cleanUpCapacity = capacity * cleanUpFactor;
    /**
     * 空Node队列高水位容量
     */
    private int hwmCleanUpCapacity = 0; // high water mark

    // LRU
    protected volatile Node head; // oldest
    protected volatile Node tail; // youngest
    // LRU清理状态位
    private volatile int lruFlag = 0; // lru list flag
    // cleanup清理状态位
    private volatile int cleanUpFlag = 0; // cleanup list flag


    private ValueLoader<K, V> loader;

    public LRUCache(LRUCacheBuilder builder, ValueLoader<K, V> loader) {
        this(builder, loader, true);
    }

    LRUCache(LRUCacheBuilder builder, ValueLoader<K, V> loader, boolean needLoader) {
        this.capacity = builder.getMaximumSize();
        this.hwmCapacity = (int) (capacity * 0.75);
        this.expireMillis = builder.getExpireMillis();

        if (needLoader && loader == null) {
            throw new IllegalArgumentException("need loader");
        }
        this.loader = loader;
        this.cleanUpFactor = Math.min(2, Math.max(10, builder.getCleanUpFactor()));
        this.cleanUpCapacity = capacity * cleanUpFactor;
        this.hwmCleanUpCapacity = (int) (cleanUpCapacity * 0.75);
        localCache = new ConcurrentHashMap<K, Entry<K, V>>(capacity);

        this.head = this.tail  = null;
    }

    public void setCountLruCache(Count<K, V> countLruCache) {
        this.countLruCache = countLruCache;
    }

    @Override
    public V get(K key) throws Exception {
        stat.incrGet();
        Entry<K, V> entry = localCache.get(key);

        // 热点数据中不存在的情况
        if (entry == null) {
            LoggerUtils.debug("热点数据不存在:%s", key);
            return getFromLoader(key, loader);
        }

        Node old = entry.node;

        // 更新LRU
        boolean offered = offerEntry(entry);

        // 数据已经失效的情况
        if (isExpire(entry, now())) {
            LoggerUtils.info("热点数据已过期:%s", entry.key);
            // 刪除元素
            localCache.remove(entry.key);
            return getFromLoader(key, loader);
        }

        // LRU数据有变更
        if (offered) {
            purge(old);
        }

        // 命中率统计
        stat.incrHits();

        return entry.value;
    }


    @Override
    public void set(K key, V value) {
        LoggerUtils.info("追加热点数据:%s", key);
        _set(key, value);
    }

    protected Entry<K, V> _set(K key, V value) {
        Entry entry = newEntry(value, key);
        // 利用putIfAbsent特性
        Entry old = localCache.putIfAbsent(key, entry);
        // 第一次追加此数据
        if (old == null) {
            stat.incrSet();
            LoggerUtils.debug("第一次追加热点数据:%s", key);
            // LRU追加
            appendTail(entry.node);
            // 高水位
            if (highWaterMark()) {
                // lru清理触发
                this.lru();
            }
            return entry;
        }

        return old;
    }

    protected Entry<K, V> getEntry(K key) {
        return localCache.get(key);
    }

    @Override
    public int size() {
        return localCache.size();
    }

    /**
     * 从Loader中加载数据
     * @param key
     * @param loader
     * @return
     * @throws Exception
     */
    protected V getFromLoader(K key, ValueLoader<K, V> loader) throws Exception {
        if (countLruCache != null) {
            return countLruCache.get(key, this);
        } else {
            // 非计数器方式，直接晋升热点数据
            return _set(key, loader.get(key)).value;
        }
    }

    /**
     * 新增Node，将Entry的Node引用指向新Node, 并添加至LRU尾部，将原Node的Entry引用删除
     * @param entry
     * @return
     */
    protected boolean offerEntry(Entry entry) {
        // LRU最近使用 != 当前元素
        if (entry != tail.entry) {
            LoggerUtils.debug("删除LRU队列中Node的Entry引用:%s", entry.key);
            Node c = entry.node;
            // 创建新Node
            Node tmp = new Node(entry);
            for (;;) {
                // Entry的Node引用调整
                if (compareAndSetNode(entry, c, tmp)) {
                    // 原Node的Entry引用删除
                    if (c != null) {
                        c.entry = null;
                    }

                    if (isExpire(entry, now())) {
                        LoggerUtils.debug("追加数据已失效:%s", entry.key);
                    } else {
                        // 添加至LRU尾部
                        appendTail(tmp);
                    }
                    break;
                }
            }
        } else {
            return false;
        }

        return true;
    }

    /**
     * 清理空Node逻辑
     * @param node
     */
    protected void purge(Node node) {
        // 追加至cleanup队列
        queue.offer(node);
        // cleanup队列过高水位
        if (queueCount.incrementAndGet() > hwmCleanUpCapacity) {
            boolean lockFlag = false;
            try {
                // cleanup队列过最大容量
                if (queueCount.get() > cleanUpCapacity) {
                    // 所有线程竞争lock
                    purgeLock.lock();
                    lockFlag = true;
                }

                boolean cas = lockFlag;
                do {
                    // 触发cleanup队列清除
                    if (compareAndSetCleanUpFlagOffset(0, 1)) {
                        // 清理cleanup队列的同时，lru队列清理标志位设置
                        for(;;) {
                            if (compareAndSetLRUFlagOffset(0, 1)) {
                                // 空Node处理
                                clean();
                                // lru逻辑
                                _lru();
                                // 跳出
                                break;
                            }
                        }
                        // 处理
                        cas = false;
                    }
                } while (cas);
            } finally {
                if (lockFlag) {
                    purgeLock.unlock();
                }
            }
        }
    }

    /**
     * 空Node队列中的元素从LRU链表中清理
     */
    private void clean() {
        LoggerUtils.info("触发cleanup队列清除:%s", queueCount.get());
        for (;;) {
            Node first = queue.poll();

            if (first == null) {
                LoggerUtils.info("cleanup队列清除完成");
                queueCount.getAndSet(0);
                cleanUpFlag = 0;
                break;
            }

            // 空节点
            if (first.isolated()) {
                continue;
            }

            Node prev = first.prev;
            Node next = first.next;
            // 不处理空Node在head的情况
            if (first != head) {
                // 指针修改
                if (prev != null) {
                    prev.next = first.next;
                }
                if (next != null) {
                    next.prev = first.prev;
                }
                first.prev = null;
                first.next = null;
                first.entry = null;
            }
            stat.incrCleanup();

        }
    }

    /**i
     * 追加Node至LRU尾部
     * @param node
     */
    protected void appendTail(Node node) {
        for (;;) {
            Node t = tail;
            if (t == null) {
                // 第一个Node
                if (compareAndSetHead(null, node))
                    tail = head;
            } else {
                // 追加Node至LRU尾部
                node.prev = t;
                if (compareAndSetTail(t, node)) {
                    t.next = node;
                    break;
                }
            }
        }
    }

    // LRU淘汰
    protected void lru() {
        boolean wFlag = false;
        try {
            // 达到最大容量
            if (full()) {
                // 竞争锁资源，其他set等待
                lruLock.lock();
                wFlag = true;
            }

            boolean cas = wFlag;
            do {
                // 竞争淘汰资源
                if (compareAndSetLRUFlagOffset(0, 1)) {
                    _lru();
                    cas = false;
                }
            } while (cas);
        } finally {
            if (wFlag) {
                lruLock.unlock();
            }
        }
    }

    private void _lru() {
        LoggerUtils.debug("触发LRU淘汰逻辑");
        for (; ; ) {
            Node h = head;
            if (h != null) {
                Node n = h.next;

                Entry<K, V> remove = h.entry;
                // 没有下一个元素
                if (n == null) {
                    lruFlag = 0;
                    break;
                }

                Entry entry = n.entry;

                // 没有满 + 下一个元素没有超期
                if (!highWaterMark() && entry != null && !isExpire(entry, now())) {
                    lruFlag = 0;
                    break;
                }
                if (compareAndSetHead(h, n)) {
                    stat.incrLRU();
                    // 淘汰成功，引用删除
                    if (remove != null) {
                        LoggerUtils.info("LRU淘汰元素:%s", remove.key);
                        localCache.remove(remove.key);
                    }
                    h.entry = null;
                    h.prev = null;
                    h.next = null;
                }
            }
        }
    }

    // 数据是否有效
    protected boolean isExpire(Entry<K, V> entry, long time) {
        return time - entry.time > expireMillis;
    }

    /**
     * 高水位警戒
     * @return
     */
    protected boolean highWaterMark() {
        return localCache.size() > this.hwmCapacity;
    }

    /**
     * 是否超出容量
     * @return
     */
    protected boolean full() {
        return localCache.size() > this.capacity;
    }

    protected Entry<K, V> newEntry(V val, K key) {
        return new Entry(val, key);
    }

    public long now() {
        return java.lang.System.currentTimeMillis();
    }

    @Override
    public ValueLoader<K, V> valueLoader() {
        return loader;
    }

    @Override
    public Stat stat() {
        return stat;
    }


    private Lock purgeLock = new ReentrantLock();
    private Lock lruLock = new ReentrantLock();

    private static final Unsafe unsafe;
    private static final long headOffset;
    private static final long tailOffset;
    private static final long nodeOffset;
    private static final long lruFlagOffset;
    private static final long cleanUpFlagOffset;

    static {
        try {
            Class<?> clazz = Unsafe.class;
            Field f = clazz.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            unsafe = (Unsafe) f.get(clazz);
            headOffset = unsafe.objectFieldOffset
                    (LRUCache.class.getDeclaredField("head"));
            tailOffset = unsafe.objectFieldOffset
                    (LRUCache.class.getDeclaredField("tail"));
            lruFlagOffset = unsafe.objectFieldOffset
                    (LRUCache.class.getDeclaredField("lruFlag"));
            cleanUpFlagOffset = unsafe.objectFieldOffset
                    (LRUCache.class.getDeclaredField("cleanUpFlag"));
            nodeOffset = unsafe.objectFieldOffset
                    (Entry.class.getDeclaredField("node"));

        } catch (Exception ex) { throw new Error(ex); }
    }

    protected final boolean compareAndSetHead(Node expect, Node update) {
        return unsafe.compareAndSwapObject(this, headOffset, expect, update);
    }

    protected final boolean compareAndSetTail(Node expect, Node update) {
        return unsafe.compareAndSwapObject(this, tailOffset, expect, update);
    }

    protected final boolean compareAndSetNode(Entry<K, V> entry, Node expect, Node update) {
        return unsafe.compareAndSwapObject(entry, nodeOffset, expect, update);
    }

    protected final boolean compareAndSetLRUFlagOffset(int expect, int update) {
        return unsafe.compareAndSwapInt(this, lruFlagOffset, expect, update);
    }

    protected final boolean compareAndSetCleanUpFlagOffset(int expect, int update) {
        return unsafe.compareAndSwapInt(this, cleanUpFlagOffset, expect, update);
    }

    static public class StatImp implements Stat {

        @Override
        public int incrGet() {
            return 0;
        }
        @Override
        public int incrLRU() {
            return 0;
        }
        @Override
        public int incrCleanup() {
            return 0;
        }
        @Override
        public int incrSet() {
            return 0;
        }
        @Override
        public int incrHits() {
            return 0;
        }

        @Override
        public void print() {

        }
    }

    static public class Stat2Imp implements Stat {

        AtomicInteger get = new AtomicInteger();

        AtomicInteger hits = new AtomicInteger();

        AtomicInteger lru = new AtomicInteger();
        AtomicInteger cleanup = new AtomicInteger();

        AtomicInteger set = new AtomicInteger();

        @Override
        public int incrGet() {
            return get.incrementAndGet();
        }
        @Override
        public int incrLRU() {
            return lru.incrementAndGet();
        }
        @Override
        public int incrCleanup() {
            return cleanup.incrementAndGet();
        }
        @Override
        public int incrSet() {
            return set.incrementAndGet();
        }

        @Override
        public int incrHits() {
            return hits.incrementAndGet();
        }

        @Override
        public void print() {
            LoggerUtils.error("Get : %s ", get.get());
            LoggerUtils.error("Hits : %s ", hits.get());
            LoggerUtils.error("Set : %s ", set.get());
            LoggerUtils.error("LRU : %s ", lru.get());
            LoggerUtils.error("cleanup : %s ", cleanup.get());
        }
    }

    /**
     * K-V Entry
     * @param <K>
     * @param <V>
     */
    static public class Entry<K, V> {
        V value;
        K key;
        Node node;
        long time;

        public Entry(V value, K key){
            this.value  = value;
            this.key 	= key;
            this.time = System.currentTimeMillis();
            this.node = new Node(this);
        }
    }

    /**
     * LRU NODE
     */
    static public class Node {
        public Node(Entry entry) {
            this.entry = entry;
        }

        Entry entry;
        Node prev;
        Node next;

        boolean isolated() {
            return prev == null && next == null;
        }
    }

}
