# lru

### 命中处理流程
![demo](p1.jpeg)

### 触发事件
 1. Cache容量达到HWM，触发LRU链表数据清理
 2. CleanUp队列容量达到HWM，触发LRU链表数据+CleanUp队列数据清理

### 入口
Main.main()
