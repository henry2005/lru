import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * @author quyan
 * @version 1.0
 * @date 2018/1/10
 * @company lzh
 * @category PACKAGE_NAME
 * @copyright copyright(c) 2015~2016
 */
public class Main {


    public static void main(String[] args) throws Exception {
        final Cache<Integer, String> cache = LRUCacheBuilder.newBuilder()
                .maximumSize(1000).cleanUpFactor(3).expire(500, TimeUnit.MILLISECONDS)
                .build(new ValueLoader<Integer, String>() {
                    @Override
                    public String get(Integer key) {
                        try {
//                            Thread.sleep(1);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                        return "";
                    }
                });


        final CountCache countCache = new CountCache(LRUCacheBuilder.newBuilder()
                .maximumSize(2000).cleanUpFactor(3).expire(500, TimeUnit.MILLISECONDS));
        countCache.setCount(3);

        ((LRUCache)cache).setCountLruCache(countCache);

        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                Random r = new Random();
                while (true) {
                    try {
                        cache.get(r.nextInt(10000));
                    } catch (Exception e) {
                        e.printStackTrace();
                        break;
                    }
                }
            }
        };

        Runnable runnable2 = new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        cache.stat().print();
                        System.out.println("=======================");
                        Thread.sleep(1000);
                    } catch (Exception e) {
                        e.printStackTrace();
                        break;
                    }
                }
            }
        };

        ExecutorService executorService = Executors.newFixedThreadPool(5);
        executorService.submit(runnable);
        executorService.submit(runnable);
        executorService.submit(runnable);
        executorService.submit(runnable);
        executorService.submit(runnable2);
    }
}
