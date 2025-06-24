import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 高性能英文软件问答系统Jaccard相似度计算器
 * 修复版本：解决内存泄漏问题
 */
public class OptimizedJaccardSimilarity {
    
    // 预编译的正则表达式模式
    private static final Pattern MIXED_PATTERN = Pattern.compile("\\b[a-zA-Z]+\\d+|\\d+[a-zA-Z]+\\b");
    private static final Pattern ENGLISH_WORD_PATTERN = Pattern.compile("\\b[a-zA-Z]{2,}\\b");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\b\\d+\\b");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private static final Pattern CLEANUP_PATTERN = Pattern.compile("[^\\w\\s\\-\\.]");
    
    // LRU缓存实现
    private static final int MAX_CACHE_SIZE = 10000;
    private static final LRUCache<String, Set<String>> TOKEN_CACHE = new LRUCache<>(MAX_CACHE_SIZE);
    private static final LRUCache<String, String> STEM_CACHE = new LRUCache<>(MAX_CACHE_SIZE);
    
    // 性能优化常量
    private static final double EPSILON = 1e-10;
    
    // 预构建的集合
    private static final Set<String> STOP_WORDS = createStopWords();
    private static final Set<String> SOFTWARE_TERMS = createSoftwareTerms();
    private static final Map<String, String> STEM_RULES = createStemRules();
    
    /**
     * 线程安全的LRU缓存实现
     */
    private static class LRUCache<K, V> {
        private final int capacity;
        private final Map<K, V> cache;
        private final ConcurrentLinkedQueue<K> accessOrder;
        private final Set<K> keySet;
        private final Object lock = new Object();
        
        public LRUCache(int capacity) {
            this.capacity = capacity;
            this.cache = new ConcurrentHashMap<>(capacity);
            this.accessOrder = new ConcurrentLinkedQueue<>();
            this.keySet = Collections.synchronizedSet(new HashSet<>());
        }
        
        public V get(K key) {
            V value = cache.get(key);
            if (value != null) {
                // 更新访问顺序
                synchronized (lock) {
                    accessOrder.remove(key);
                    accessOrder.offer(key);
                }
            }
            return value;
        }
        
        public void put(K key, V value) {
            synchronized (lock) {
                if (cache.containsKey(key)) {
                    // 更新已存在的键
                    accessOrder.remove(key);
                    accessOrder.offer(key);
                    cache.put(key, value);
                } else {
                    // 添加新键
                    if (cache.size() >= capacity) {
                        // 移除最久未使用的项
                        K eldest = accessOrder.poll();
                        if (eldest != null) {
                            cache.remove(eldest);
                            keySet.remove(eldest);
                        }
                    }
                    cache.put(key, value);
                    keySet.add(key);
                    accessOrder.offer(key);
                }
            }
        }
        
        public void clear() {
            synchronized (lock) {
                cache.clear();
                accessOrder.clear();
                keySet.clear();
            }
        }
        
        public int size() {
            return cache.size();
        }
        
        public boolean containsKey(K key) {
            return cache.containsKey(key);
        }
    }
    
    private static Set<String> createStopWords() {
        return new HashSet<>(Arrays.asList(
            "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for", "of", "with", "by", 
            "is", "are", "was", "were", "be", "been", "have", "has", "had", "do", "does", "did", 
            "will", "would", "could", "should", "may", "might", "can", "this", "that", "these", "those"
        ));
    }
    
    private static Set<String> createSoftwareTerms() {
        return new HashSet<>(Arrays.asList(
            "database", "algorithm", "software", "hardware", "network", "security", "encryption",
            "programming", "debugging", "testing", "deployment", "configuration", "installation",
            "analytics", "kubernetes", "docker", "microservices", "api", "json", "xml", "html",
            "javascript", "python", "java", "framework", "library", "repository", "version",
            "interface", "dashboard", "menu", "button", "dialog", "settings", "preferences",
            "login", "logout", "signup", "password", "username", "email", "verification"
        ));
    }
    
    private static Map<String, String> createStemRules() {
        Map<String, String> rules = new HashMap<>();
        
        // 动词变化
        String[][] verbRules = {
            {"showing", "show"}, {"started", "start"}, {"running", "run"}, {"connecting", "connect"},
            {"installing", "install"}, {"configuring", "configure"}, {"processing", "process"},
            {"loading", "load"}, {"saving", "save"}, {"updating", "update"}, {"creating", "create"},
            {"deleting", "delete"}, {"testing", "test"}, {"debugging", "debug"}, {"monitoring", "monitor"},
            {"editing", "edit"}, {"authenticating", "authenticate"}, {"authorizing", "authorize"},
            {"deploying", "deploy"}, {"migrating", "migrate"}, {"working", "work"}, {"failing", "fail"},
            {"crashing", "crash"}, {"logging", "log"}, {"backing", "backup"}, {"recovering", "recover"},
            {"resetting", "reset"}
        };
        
        // 添加动词的所有形式
        for (String[] rule : verbRules) {
            String base = rule[1];
            String ing = rule[0];
            rules.put(ing, base);
            rules.put(base + "s", base);
            rules.put(base + "ed", base);
        }
        
        // 名词复数
        String[][] nounRules = {
            {"users", "user"}, {"systems", "system"}, {"passwords", "password"}, {"accounts", "account"},
            {"files", "file"}, {"errors", "error"}, {"problems", "problem"}, {"issues", "issue"},
            {"applications", "application"}, {"connections", "connection"}, {"configurations", "configuration"},
            {"permissions", "permission"}, {"settings", "setting"}, {"databases", "database"},
            {"servers", "server"}, {"networks", "network"}, {"services", "service"}, {"processes", "process"}
        };
        
        for (String[] rule : nounRules) {
            rules.put(rule[0], rule[1]);
        }
        
        // 特殊映射
        rules.put("ran", "run");
        rules.put("went", "go");
        rules.put("came", "come");
        rules.put("saw", "see");
        rules.put("got", "get");
        
        return rules;
    }
    
    /**
     * 清除所有缓存
     */
    public static void clearCache() {
        TOKEN_CACHE.clear();
        STEM_CACHE.clear();
    }
    
    /**
     * 获取缓存统计信息
     */
    public static Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("tokenCacheSize", TOKEN_CACHE.size());
        stats.put("stemCacheSize", STEM_CACHE.size());
        stats.put("maxCacheSize", MAX_CACHE_SIZE);
        
        // 估算内存使用
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        stats.put("memoryUsagePercent", (usedMemory * 100.0) / maxMemory);
        
        return stats;
    }
    
    /**
     * 计算Jaccard相似度
     */
    public static double calculateJaccardSimilarity(Set<String> set1, Set<String> set2) {
        if (set1 == null || set2 == null || set1.isEmpty() || set2.isEmpty()) {
            return 0.0;
        }
        
        // 选择较小的集合进行遍历
        Set<String> smaller = set1.size() <= set2.size() ? set1 : set2;
        Set<String> larger = set1.size() > set2.size() ? set1 : set2;
        
        int intersectionSize = 0;
        for (String element : smaller) {
            if (larger.contains(element)) {
                intersectionSize++;
            }
        }
        
        int unionSize = set1.size() + set2.size() - intersectionSize;
        return unionSize == 0 ? 0.0 : (double) intersectionSize / unionSize;
    }
    
    /**
     * 计算非对称Jaccard相似度（查询覆盖率）
     */
    public static double calculateAsymmetricJaccardSimilarity(Set<String> querySet, Set<String> documentSet) {
        if (querySet == null || documentSet == null || querySet.isEmpty()) {
            return 0.0;
        }
        
        int coveredCount = 0;
        for (String queryToken : querySet) {
            if (documentSet.contains(queryToken)) {
                coveredCount++;
            }
        }
        
        return (double) coveredCount / querySet.size();
    }
    
    /**
     * 字符串相似度计算（带词干提取）
     */
    public static double calculateStringJaccardSimilarity(String text1, String text2) {
        if (text1 == null || text2 == null) {
            return 0.0;
        }
        
        if (text1.equals(text2)) {
            return text1.trim().isEmpty() ? 0.0 : 1.0;
        }
        
        Set<String> tokens1 = tokenize(text1, true);
        Set<String> tokens2 = tokenize(text2, true);
        return calculateJaccardSimilarity(tokens1, tokens2);
    }
    
    /**
     * 字符串相似度计算（不带词干提取）
     */
    public static double calculateSimilarityWithoutStemming(String text1, String text2) {
        if (text1 == null || text2 == null) {
            return 0.0;
        }
        
        if (text1.equals(text2)) {
            return text1.trim().isEmpty() ? 0.0 : 1.0;
        }
        
        Set<String> tokens1 = tokenize(text1, false);
        Set<String> tokens2 = tokenize(text2, false);
        return calculateJaccardSimilarity(tokens1, tokens2);
    }
    
    /**
     * 非对称字符串相似度（带词干提取）
     */
    public static double calculateAsymmetricStringSimilarity(String query, String document) {
        if (query == null || document == null) {
            return 0.0;
        }
        
        if (query.equals(document)) {
            return query.trim().isEmpty() ? 0.0 : 1.0;
        }
        
        Set<String> queryTokens = tokenize(query, true);
        Set<String> documentTokens = tokenize(document, true);
        return calculateAsymmetricJaccardSimilarity(queryTokens, documentTokens);
    }
    
    /**
     * 非对称字符串相似度（不带词干提取）
     */
    public static double calculateAsymmetricSimilarityWithoutStemming(String query, String document) {
        if (query == null || document == null) {
            return 0.0;
        }
        
        if (query.equals(document)) {
            return query.trim().isEmpty() ? 0.0 : 1.0;
        }
        
        Set<String> queryTokens = tokenize(query, false);
        Set<String> documentTokens = tokenize(document, false);
        return calculateAsymmetricJaccardSimilarity(queryTokens, documentTokens);
    }
    
    /**
     * 混合相似度计算
     */
    public static double calculateHybridSimilarity(String query, String document, double asymmetricWeight) {
        if (asymmetricWeight < 0.0 || asymmetricWeight > 1.0) {
            throw new IllegalArgumentException("Asymmetric weight must be between 0 and 1");
        }
        
        double symmetricSimilarity = calculateStringJaccardSimilarity(query, document);
        double asymmetricSimilarity = calculateAsymmetricStringSimilarity(query, document);
        
        return asymmetricWeight * asymmetricSimilarity + (1.0 - asymmetricWeight) * symmetricSimilarity;
    }
    
    /**
     * 生成缓存键（优化长文本）
     */
    private static String getCacheKey(String text, boolean useStemming) {
        if (text.length() > 200) {
            // 对长文本使用哈希值
            return Objects.hash(text) + ":" + useStemming;
        }
        return text + ":" + useStemming;
    }
    
    /**
     * 统一的分词方法
     */
    private static Set<String> tokenize(String text, boolean useStemming) {
        if (text == null || text.isEmpty()) {
            return Collections.emptySet();
        }
        
        // 生成缓存键
        String cacheKey = getCacheKey(text, useStemming);
        
        // 检查缓存
        Set<String> cached = TOKEN_CACHE.get(cacheKey);
        if (cached != null) {
            return new HashSet<>(cached); // 返回副本
        }
        
        // 预处理
        String processed = preprocessText(text);
        if (processed.isEmpty()) {
            return Collections.emptySet();
        }
        
        // 提取tokens
        Set<String> tokens = new HashSet<>();
        boolean[] processed_chars = new boolean[processed.length()];
        
        // 1. 处理混合词
        Matcher mixedMatcher = MIXED_PATTERN.matcher(processed);
        while (mixedMatcher.find()) {
            String word = mixedMatcher.group().toLowerCase();
            if (useStemming) {
                word = stemWord(word);
            }
            tokens.add(word);
            markProcessed(processed_chars, mixedMatcher.start(), mixedMatcher.end());
        }
        
        // 2. 处理英文单词
        Matcher wordMatcher = ENGLISH_WORD_PATTERN.matcher(processed);
        while (wordMatcher.find()) {
            if (!isProcessed(processed_chars, wordMatcher.start(), wordMatcher.end())) {
                String word = wordMatcher.group().toLowerCase();
                if (useStemming) {
                    word = stemWord(word);
                }
                tokens.add(word);
                markProcessed(processed_chars, wordMatcher.start(), wordMatcher.end());
            }
        }
        
        // 3. 处理数字
        Matcher numberMatcher = NUMBER_PATTERN.matcher(processed);
        while (numberMatcher.find()) {
            if (!isProcessed(processed_chars, numberMatcher.start(), numberMatcher.end())) {
                tokens.add(numberMatcher.group());
                markProcessed(processed_chars, numberMatcher.start(), numberMatcher.end());
            }
        }
        
        // 缓存结果（不可变集合）
        Set<String> immutableTokens = Collections.unmodifiableSet(new HashSet<>(tokens));
        TOKEN_CACHE.put(cacheKey, immutableTokens);
        
        return tokens;
    }
    
    /**
     * 文本预处理
     */
    private static String preprocessText(String text) {
        String cleaned = CLEANUP_PATTERN.matcher(text).replaceAll(" ");
        return WHITESPACE_PATTERN.matcher(cleaned).replaceAll(" ").trim();
    }
    
    /**
     * 词干提取
     */
    private static String stemWord(String word) {
        if (word == null || word.length() <= 2) {
            return word;
        }
        
        // 检查停用词和专业术语
        if (STOP_WORDS.contains(word) || SOFTWARE_TERMS.contains(word)) {
            return word;
        }
        
        // 查找缓存
        String cached = STEM_CACHE.get(word);
        if (cached != null) {
            return cached;
        }
        
        // 查找预定义规则
        String stemmed = STEM_RULES.get(word);
        if (stemmed == null) {
            // 简单的后缀处理
            if (word.endsWith("ing") && word.length() > 5) {
                stemmed = word.substring(0, word.length() - 3);
            } else if (word.endsWith("ed") && word.length() > 4) {
                stemmed = word.substring(0, word.length() - 2);
            } else if (word.endsWith("s") && word.length() > 3 && !word.endsWith("ss")) {
                stemmed = word.substring(0, word.length() - 1);
            } else {
                stemmed = word;
            }
        }
        
        // 缓存结果
        STEM_CACHE.put(word, stemmed);
        
        return stemmed;
    }
    
    private static boolean isProcessed(boolean[] processed, int start, int end) {
        for (int i = start; i < end && i < processed.length; i++) {
            if (processed[i]) return true;
        }
        return false;
    }
    
    private static void markProcessed(boolean[] processed, int start, int end) {
        for (int i = start; i < end && i < processed.length; i++) {
            processed[i] = true;
        }
    }
    
    /**
     * 知识库匹配器（支持延迟加载）
     */
    public static class KnowledgeBaseMatcher {
        private final List<String> knowledgeBase;
        private final Map<Integer, Set<String>> preprocessedCache;
        private final boolean lazyLoad;
        private final int cacheSize;
        
        public KnowledgeBaseMatcher(List<String> knowledgeBase) {
            this(knowledgeBase, false, 1000);
        }
        
        public KnowledgeBaseMatcher(List<String> knowledgeBase, boolean lazyLoad, int cacheSize) {
            this.knowledgeBase = Collections.unmodifiableList(new ArrayList<>(knowledgeBase));
            this.lazyLoad = lazyLoad;
            this.cacheSize = cacheSize;
            
            if (lazyLoad) {
                // 延迟加载模式：使用LRU缓存
                this.preprocessedCache = Collections.synchronizedMap(
                    new LinkedHashMap<Integer, Set<String>>(16, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<Integer, Set<String>> eldest) {
                            return size() > cacheSize;
                        }
                    }
                );
            } else {
                // 预加载模式：一次性处理所有文档
                Map<Integer, Set<String>> tempCache = new HashMap<>();
                for (int i = 0; i < knowledgeBase.size(); i++) {
                    tempCache.put(i, tokenize(knowledgeBase.get(i), true));
                }
                this.preprocessedCache = Collections.unmodifiableMap(tempCache);
            }
        }
        
        private Set<String> getDocumentTokens(int index) {
            if (lazyLoad) {
                return preprocessedCache.computeIfAbsent(index, 
                    i -> tokenize(knowledgeBase.get(i), true));
            } else {
                return preprocessedCache.get(index);
            }
        }
        
        public List<MatchResult> findBestMatches(String query, int topK) {
            return findBestMatches(query, topK, 0.0, false);
        }
        
        public List<MatchResult> findBestMatches(String query, int topK, double minSimilarity, boolean useAsymmetric) {
            if (query == null || topK <= 0) {
                return Collections.emptyList();
            }
            
            topK = Math.min(topK, knowledgeBase.size());
            Set<String> queryTokens = tokenize(query, true);
            
            PriorityQueue<MatchResult> topResults = new PriorityQueue<>(
                    topK, Comparator.comparingDouble(r -> r.similarity));
            
            for (int i = 0; i < knowledgeBase.size(); i++) {
                Set<String> docTokens = getDocumentTokens(i);
                
                double similarity;
                if (useAsymmetric) {
                    similarity = calculateAsymmetricJaccardSimilarity(queryTokens, docTokens);
                } else {
                    double asymmetricSim = calculateAsymmetricJaccardSimilarity(queryTokens, docTokens);
                    double symmetricSim = calculateJaccardSimilarity(queryTokens, docTokens);
                    similarity = 0.7 * asymmetricSim + 0.3 * symmetricSim;
                }
                
                if (similarity >= minSimilarity) {
                    MatchResult result = new MatchResult(i, knowledgeBase.get(i), similarity);
                    
                    if (topResults.size() < topK) {
                        topResults.offer(result);
                    } else if (similarity > topResults.peek().similarity) {
                        topResults.poll();
                        topResults.offer(result);
                    }
                }
            }
            
            return topResults.stream()
                    .sorted((a, b) -> Double.compare(b.similarity, a.similarity))
                    .collect(Collectors.toList());
        }
        
        public void clearCache() {
            if (lazyLoad) {
                preprocessedCache.clear();
            }
        }
        
        public int getCacheSize() {
            return preprocessedCache.size();
        }
    }
    
    /**
     * 匹配结果
     */
    public static class MatchResult {
        public final int index;
        public final String text;
        public final double similarity;
        
        public MatchResult(int index, String text, double similarity) {
            this.index = index;
            this.text = text;
            this.similarity = similarity;
        }
        
        @Override
        public String toString() {
            return String.format("匹配结果{索引=%d, 相似度=%.6f, 文本='%s'}", 
                    index, similarity, 
                    text.length() > 50 ? text.substring(0, 47) + "..." : text);
        }
    }
    
    /**
     * 相似度对比结果
     */
    public static class SimilarityComparison {
        public final String text1;
        public final String text2;
        public final double withoutStemming;
        public final double withStemming;
        public final double improvement;
        
        public SimilarityComparison(String text1, String text2) {
            this.text1 = text1;
            this.text2 = text2;
            this.withoutStemming = calculateSimilarityWithoutStemming(text1, text2);
            this.withStemming = calculateStringJaccardSimilarity(text1, text2);
            this.improvement = withStemming - withoutStemming;
        }
        
        @Override
        public String toString() {
            return String.format(
                "文本1: \"%s\"\n文本2: \"%s\"\n不使用词干提取: %.6f\n使用词干提取: %.6f\n改进: %+.6f",
                text1.length() > 50 ? text1.substring(0, 47) + "..." : text1,
                text2.length() > 50 ? text2.substring(0, 47) + "..." : text2,
                withoutStemming, withStemming, improvement
            );
        }
    }
    
    // ==================== 演示代码部分 ====================
    
    public static void main(String[] args) {
        System.out.println("=== 高性能英文软件问答系统 ===");
        
        demonstrateBasicUsage();
        demonstrateSimilarityComparison();
        demonstrateKnowledgeBaseMatching();
        demonstratePerformanceTest();
        demonstrateBatchComparison();
        demonstrateMemorySafety();
        
        clearCache();
    }
    
    private static void demonstrateBasicUsage() {
        System.out.println("\n=== 非对称与对称Jaccard相似度演示 ===");
        
        String shortQuery = "user login password forgot";
        String longDocument = "User forgot login password how to recover account reset guide step by step tutorial";
        
        System.out.printf("查询（短文本）: \"%s\"%n", shortQuery);
        System.out.printf("文档（长文本）: \"%s\"%n", longDocument);
        
        // 预热JVM
        for (int i = 0; i < 1000; i++) {
            calculateSimilarityWithoutStemming(shortQuery, longDocument);
            calculateAsymmetricSimilarityWithoutStemming(shortQuery, longDocument);
            calculateStringJaccardSimilarity(shortQuery, longDocument);
            calculateAsymmetricStringSimilarity(shortQuery, longDocument);
        }
        
        // 1. 不采用词干提取的比较（含耗时）
        System.out.println("\n【1. 不采用词干提取】");
        long startTime = System.nanoTime();
        double symmetricNoStem = calculateSimilarityWithoutStemming(shortQuery, longDocument);
        long symNoStemTime = System.nanoTime() - startTime;
        
        startTime = System.nanoTime();
        double asymmetricNoStem = calculateAsymmetricSimilarityWithoutStemming(shortQuery, longDocument);
        long asymNoStemTime = System.nanoTime() - startTime;
        
        System.out.printf("  A. 非对称Jaccard相似度: %.6f (耗时: %.3f μs)%n", asymmetricNoStem, asymNoStemTime / 1000.0);
        System.out.printf("  B. 对称Jaccard相似度: %.6f (耗时: %.3f μs)%n", symmetricNoStem, symNoStemTime / 1000.0);
        System.out.printf("  差异(A-B): %+.6f (%.1f%%)%n", 
            asymmetricNoStem - symmetricNoStem, 
            (asymmetricNoStem - symmetricNoStem) * 100);
        
        // 2. 采用词干提取的比较（含耗时）
        System.out.println("\n【2. 采用词干提取】");
        startTime = System.nanoTime();
        double symmetricWithStem = calculateStringJaccardSimilarity(shortQuery, longDocument);
        long symWithStemTime = System.nanoTime() - startTime;
        
        startTime = System.nanoTime();
        double asymmetricWithStem = calculateAsymmetricStringSimilarity(shortQuery, longDocument);
        long asymWithStemTime = System.nanoTime() - startTime;
        
        System.out.printf("  AA. 非对称Jaccard相似度: %.6f (耗时: %.3f μs)%n", asymmetricWithStem, asymWithStemTime / 1000.0);
        System.out.printf("  BB. 对称Jaccard相似度: %.6f (耗时: %.3f μs)%n", symmetricWithStem, symWithStemTime / 1000.0);
        System.out.printf("  差异(AA-BB): %+.6f (%.1f%%)%n", 
            asymmetricWithStem - symmetricWithStem,
            (asymmetricWithStem - symmetricWithStem) * 100);
        
        // 3. 词干提取的效果比较
        System.out.println("\n【3. 词干提取效果比较】");
        System.out.printf("  非对称相似度提升(AA-A): %+.6f (%.1f%%)%n", 
            asymmetricWithStem - asymmetricNoStem,
            asymmetricNoStem > 0 ? (asymmetricWithStem - asymmetricNoStem) / asymmetricNoStem * 100 : 0);
        System.out.printf("  对称相似度提升(BB-B): %+.6f (%.1f%%)%n", 
            symmetricWithStem - symmetricNoStem,
            symmetricNoStem > 0 ? (symmetricWithStem - symmetricNoStem) / symmetricNoStem * 100 : 0);
        
        // 4. 性能比较
        System.out.println("\n【4. 性能比较】");
        System.out.printf("  非对称计算速度比: %.2fx (有词干/无词干)%n", (double)asymNoStemTime / asymWithStemTime);
        System.out.printf("  对称计算速度比: %.2fx (有词干/无词干)%n", (double)symNoStemTime / symWithStemTime);
        System.out.printf("  非对称vs对称速度比: %.2fx (无词干), %.2fx (有词干)%n", 
            (double)symNoStemTime / asymNoStemTime, (double)symWithStemTime / asymWithStemTime);
        
        // 显示详细的token分析
        System.out.println("\n【4. Token分析】");
        Set<String> queryTokensNoStem = tokenize(shortQuery, false);
        Set<String> docTokensNoStem = tokenize(longDocument, false);
        Set<String> queryTokensWithStem = tokenize(shortQuery, true);
        Set<String> docTokensWithStem = tokenize(longDocument, true);
        
        System.out.printf("查询tokens（无词干）: %s%n", queryTokensNoStem);
        System.out.printf("查询tokens（有词干）: %s%n", queryTokensWithStem);
        System.out.printf("文档tokens（无词干）: %s%n", 
            docTokensNoStem.size() > 10 ? 
            docTokensNoStem.stream().limit(10).collect(Collectors.toList()) + "..." : 
            docTokensNoStem);
        System.out.printf("文档tokens（有词干）: %s%n", 
            docTokensWithStem.size() > 10 ? 
            docTokensWithStem.stream().limit(10).collect(Collectors.toList()) + "..." : 
            docTokensWithStem);
        
        // 业务案例测试
        System.out.println("\n=== 业务案例测试 ===");
        String businessDoc = "After purchase log in the 14 Days left in trial is still displayed How can I activate license subscription show me on a free trial account 14-day trial period stay in trial";
        
        String[] businessQueries = {
            "After purchase and log in the 14 Days left in trial is still displayed",
            "I have already purchased a subscription why is it showing me on a free trial account", 
            "I have already purchased but why does it still show the remaining days of trial"
        };
        
        for (int i = 0; i < businessQueries.length; i++) {
            String query = businessQueries[i];
            System.out.printf("\n业务查询 %d: \"%s\"%n", i + 1, 
                query.length() > 60 ? query.substring(0, 57) + "..." : query);
            
            // 四种相似度对比
            double symNoStem = calculateSimilarityWithoutStemming(query, businessDoc);
            double asymNoStem = calculateAsymmetricSimilarityWithoutStemming(query, businessDoc);
            double symWithStem = calculateStringJaccardSimilarity(query, businessDoc);
            double asymWithStem = calculateAsymmetricStringSimilarity(query, businessDoc);
            
            System.out.println("  不使用词干提取:");
            System.out.printf("    非对称: %.6f | 对称: %.6f | 差异: %+.6f%n", 
                asymNoStem, symNoStem, asymNoStem - symNoStem);
            System.out.println("  使用词干提取:");
            System.out.printf("    非对称: %.6f | 对称: %.6f | 差异: %+.6f%n", 
                asymWithStem, symWithStem, asymWithStem - symWithStem);
            System.out.printf("  词干提取效果: 非对称提升 %+.6f | 对称提升 %+.6f%n",
                asymWithStem - asymNoStem, symWithStem - symNoStem);
        }
    }
    
    private static void demonstrateSimilarityComparison() {
        System.out.println("\n=== 词干提取效果对比 ===");
        
        String text1 = "The system is running and showing connected users";
        String text2 = "System runs and shows connection of users";
        
        System.out.printf("文本1: \"%s\"%n", text1);
        System.out.printf("文本2: \"%s\"%n", text2);
        
        // 详细的四种情况对比
        System.out.println("\n【对称相似度对比】");
        double symNoStem = calculateSimilarityWithoutStemming(text1, text2);
        double symWithStem = calculateStringJaccardSimilarity(text1, text2);
        System.out.printf("  B. 不使用词干提取: %.6f%n", symNoStem);
        System.out.printf("  BB. 使用词干提取: %.6f%n", symWithStem);
        System.out.printf("  提升效果: %+.6f (%.1f%%)%n", 
            symWithStem - symNoStem,
            symNoStem > 0 ? (symWithStem - symNoStem) / symNoStem * 100 : 0);
        
        System.out.println("\n【非对称相似度对比】");
        double asymNoStem = calculateAsymmetricSimilarityWithoutStemming(text1, text2);
        double asymWithStem = calculateAsymmetricStringSimilarity(text1, text2);
        System.out.printf("  A. 不使用词干提取: %.6f%n", asymNoStem);
        System.out.printf("  AA. 使用词干提取: %.6f%n", asymWithStem);
        System.out.printf("  提升效果: %+.6f (%.1f%%)%n", 
            asymWithStem - asymNoStem,
            asymNoStem > 0 ? (asymWithStem - asymNoStem) / asymNoStem * 100 : 0);
        
        // 显示词干提取效果
        System.out.println("\n【词干提取示例】");
        String[] testWords = {
            "running", "showing", "connected", "users", "systems", "runs", "shows", "connection"
        };
        
        System.out.println("原词 -> 词干:");
        for (String word : testWords) {
            String stemmed = stemWord(word);
            boolean changed = !word.equals(stemmed);
            System.out.printf("  %-15s -> %-15s %s%n", word, stemmed, changed ? "[已转换]" : "[未变化]");
        }
        
        // 显示token变化
        System.out.println("\n【Token分析】");
        Set<String> tokens1NoStem = tokenize(text1, false);
        Set<String> tokens2NoStem = tokenize(text2, false);
        Set<String> tokens1WithStem = tokenize(text1, true);
        Set<String> tokens2WithStem = tokenize(text2, true);
        
        System.out.printf("文本1 tokens（无词干）: %s%n", tokens1NoStem);
        System.out.printf("文本1 tokens（有词干）: %s%n", tokens1WithStem);
        System.out.printf("文本2 tokens（无词干）: %s%n", tokens2NoStem);
        System.out.printf("文本2 tokens（有词干）: %s%n", tokens2WithStem);
        
        // 计算交集变化
        Set<String> intersectNoStem = new HashSet<>(tokens1NoStem);
        intersectNoStem.retainAll(tokens2NoStem);
        Set<String> intersectWithStem = new HashSet<>(tokens1WithStem);
        intersectWithStem.retainAll(tokens2WithStem);
        
        System.out.printf("\n共同tokens（无词干）: %s (数量: %d)%n", intersectNoStem, intersectNoStem.size());
        System.out.printf("共同tokens（有词干）: %s (数量: %d)%n", intersectWithStem, intersectWithStem.size());
        System.out.printf("交集增加: %d 个词%n", intersectWithStem.size() - intersectNoStem.size());
    }
    
    private static void demonstrateKnowledgeBaseMatching() {
        System.out.println("\n=== 知识库匹配演示 ===");
        
        List<String> knowledgeBase = Arrays.asList(
            "User forgot login password how to recover",
            "How to reset user password and recover account",
            "Database connection failure troubleshooting guide",
            "Software installation and deployment common issues",
            "User permission management and role configuration",
            "System performance monitoring and optimization methods",
            "After purchase log in the 14 Days left in trial is still displayed How can I activate license subscription"
        );
        
        String[] queries = {
            "forgot password",
            "database connection failed",
            "I have already purchased but why does it still show trial"
        };
        
        // 对每个查询展示四种方法的对比
        for (int q = 0; q < queries.length; q++) {
            String query = queries[q];
            System.out.printf("\n查询 %d: '%s'%n", q + 1, query);
            System.out.println("-".repeat(80));
            
            // 获取查询的tokens
            Set<String> queryTokensNoStem = tokenize(query, false);
            Set<String> queryTokensWithStem = tokenize(query, true);
            System.out.printf("查询tokens（无词干）: %s%n", queryTokensNoStem);
            System.out.printf("查询tokens（有词干）: %s%n", queryTokensWithStem);
            
            // 为每个文档计算四种相似度
            System.out.println("\n知识库文档匹配结果:");
            System.out.println("编号 | A(非对称-无词干) | B(对称-无词干) | AA(非对称-有词干) | BB(对称-有词干) | 文档");
            System.out.println("-".repeat(100));
            
            List<DocumentScore> scores = new ArrayList<>();
            
            for (int i = 0; i < knowledgeBase.size(); i++) {
                String doc = knowledgeBase.get(i);
                
                // 计算四种相似度
                double asymNoStem = calculateAsymmetricSimilarityWithoutStemming(query, doc);
                double symNoStem = calculateSimilarityWithoutStemming(query, doc);
                double asymWithStem = calculateAsymmetricStringSimilarity(query, doc);
                double symWithStem = calculateStringJaccardSimilarity(query, doc);
                
                scores.add(new DocumentScore(i, doc, asymNoStem, symNoStem, asymWithStem, symWithStem));
            }
            
            // 按照AA（非对称-有词干）排序
            scores.sort((a, b) -> Double.compare(b.asymWithStem, a.asymWithStem));
            
            // 显示结果
            for (DocumentScore score : scores) {
                System.out.printf("  %2d | %17.4f | %15.4f | %18.4f | %16.4f | %s%n",
                    score.index + 1,
                    score.asymNoStem,
                    score.symNoStem,
                    score.asymWithStem,
                    score.symWithStem,
                    score.doc.length() > 40 ? score.doc.substring(0, 37) + "..." : score.doc
                );
            }
            
            // 最佳匹配分析
            DocumentScore best = scores.get(0);
            System.out.println("\n最佳匹配分析:");
            System.out.printf("  文档: \"%s\"%n", best.doc);
            System.out.printf("  非对称相似度提升: %.4f -> %.4f (+%.4f)%n", 
                best.asymNoStem, best.asymWithStem, best.asymWithStem - best.asymNoStem);
            System.out.printf("  对称相似度提升: %.4f -> %.4f (+%.4f)%n", 
                best.symNoStem, best.symWithStem, best.symWithStem - best.symNoStem);
            
            // 显示最佳匹配的token分析
            Set<String> docTokensNoStem = tokenize(best.doc, false);
            Set<String> docTokensWithStem = tokenize(best.doc, true);
            
            Set<String> intersectNoStem = new HashSet<>(queryTokensNoStem);
            intersectNoStem.retainAll(docTokensNoStem);
            Set<String> intersectWithStem = new HashSet<>(queryTokensWithStem);
            intersectWithStem.retainAll(docTokensWithStem);
            
            System.out.printf("  匹配tokens（无词干）: %s%n", intersectNoStem);
            System.out.printf("  匹配tokens（有词干）: %s%n", intersectWithStem);
        }
        
        // 测试不同的匹配策略
        System.out.println("\n=== 不同匹配策略对比 ===");
        
        // 创建四个不同配置的匹配器
        System.out.println("\n创建不同配置的匹配器进行对比...");
        KnowledgeBaseMatcher matcher = new KnowledgeBaseMatcher(knowledgeBase, true, 100);
        
        String testQuery = "user forgot password cannot login";
        System.out.printf("\n测试查询: '%s'%n", testQuery);
        
        // 先展示查询的tokens变化
        Set<String> testQueryTokensNoStem = tokenize(testQuery, false);
        Set<String> testQueryTokensWithStem = tokenize(testQuery, true);
        System.out.printf("查询tokens（无词干）: %s%n", testQueryTokensNoStem);
        System.out.printf("查询tokens（有词干）: %s%n", testQueryTokensWithStem);
        
        // 策略1: 默认混合模式（70%非对称 + 30%对称）- 使用词干提取
        System.out.println("\n策略1: 混合模式【使用词干提取】（70%非对称AA + 30%对称BB）");
        List<MatchResult> results1 = matcher.findBestMatches(testQuery, 3, 0.1, false);
        for (int i = 0; i < results1.size(); i++) {
            MatchResult r = results1.get(i);
            // 计算各分量以便展示
            Set<String> docTokens = tokenize(r.text, true);
            double asymSim = calculateAsymmetricJaccardSimilarity(testQueryTokensWithStem, docTokens);
            double symSim = calculateJaccardSimilarity(testQueryTokensWithStem, docTokens);
            System.out.printf("  %d. 综合相似度: %.6f (非对称AA=%.3f, 对称BB=%.3f) - %s%n", 
                i + 1, r.similarity, asymSim, symSim,
                r.text.length() > 50 ? r.text.substring(0, 47) + "..." : r.text);
        }
        
        // 策略2: 纯非对称模式 - 使用词干提取
        System.out.println("\n策略2: 纯非对称模式【使用词干提取】（100%非对称AA）");
        List<MatchResult> results2 = matcher.findBestMatches(testQuery, 3, 0.1, true);
        for (int i = 0; i < results2.size(); i++) {
            MatchResult r = results2.get(i);
            System.out.printf("  %d. 非对称相似度AA: %.6f - %s%n", 
                i + 1, r.similarity, 
                r.text.length() > 50 ? r.text.substring(0, 47) + "..." : r.text);
        }
        
        // 额外展示：如果不使用词干提取的对比
        System.out.println("\n对比：如果不使用词干提取的结果");
        for (int i = 0; i < Math.min(3, knowledgeBase.size()); i++) {
            String doc = knowledgeBase.get(i);
            double asymNoStem = calculateAsymmetricSimilarityWithoutStemming(testQuery, doc);
            double symNoStem = calculateSimilarityWithoutStemming(testQuery, doc);
            double mixedNoStem = 0.7 * asymNoStem + 0.3 * symNoStem;
            System.out.printf("  文档%d: 混合相似度=%.6f (非对称A=%.3f, 对称B=%.3f)%n", 
                i + 1, mixedNoStem, asymNoStem, symNoStem);
        }
        
        // 显示缓存使用情况
        System.out.printf("\n延迟加载缓存大小: %d%n", matcher.getCacheSize());
    }
    
    // 辅助类：存储文档的四种相似度得分
    private static class DocumentScore {
        final int index;
        final String doc;
        final double asymNoStem;
        final double symNoStem;
        final double asymWithStem;
        final double symWithStem;
        
        DocumentScore(int index, String doc, double asymNoStem, double symNoStem, 
                     double asymWithStem, double symWithStem) {
            this.index = index;
            this.doc = doc;
            this.asymNoStem = asymNoStem;
            this.symNoStem = symNoStem;
            this.asymWithStem = asymWithStem;
            this.symWithStem = symWithStem;
        }
    }
    
    private static void demonstratePerformanceTest() {
        System.out.println("\n=== 性能测试 ===");
        
        List<String> kb = IntStream.range(0, 1000)
                .mapToObj(i -> "测试文档 " + i + " 包含一些内容 test document with some content")
                .collect(Collectors.toList());
        
        // 使用延迟加载以节省内存
        KnowledgeBaseMatcher matcher = new KnowledgeBaseMatcher(kb, true, 100);
        
        // 预热
        for (int i = 0; i < 100; i++) {
            matcher.findBestMatches("测试文档", 5);
        }
        
        // 测试不同的查询类型
        String[] testQueries = {
            "测试内容",
            "test document",
            "测试文档 500 包含一些内容",
            "completely different content that won't match"
        };
        
        System.out.println("不同查询的性能测试（1000次/查询）:");
        System.out.println("-".repeat(80));
        
        for (String query : testQueries) {
            System.out.printf("\n查询: '%s'%n", query);
            
            // 测试四种计算方法的性能
            // 1. 非对称无词干
            long start = System.nanoTime();
            for (int i = 0; i < 1000; i++) {
                calculateAsymmetricSimilarityWithoutStemming(query, kb.get(i % kb.size()));
            }
            long asymNoStemTime = System.nanoTime() - start;
            
            // 2. 对称无词干
            start = System.nanoTime();
            for (int i = 0; i < 1000; i++) {
                calculateSimilarityWithoutStemming(query, kb.get(i % kb.size()));
            }
            long symNoStemTime = System.nanoTime() - start;
            
            // 3. 非对称有词干
            start = System.nanoTime();
            for (int i = 0; i < 1000; i++) {
                calculateAsymmetricStringSimilarity(query, kb.get(i % kb.size()));
            }
            long asymWithStemTime = System.nanoTime() - start;
            
            // 4. 对称有词干
            start = System.nanoTime();
            for (int i = 0; i < 1000; i++) {
                calculateStringJaccardSimilarity(query, kb.get(i % kb.size()));
            }
            long symWithStemTime = System.nanoTime() - start;
            
            System.out.printf("  A (非对称-无词干): %.2f ms (%.2f μs/次)%n", 
                asymNoStemTime / 1_000_000.0, asymNoStemTime / 1000.0 / 1000);
            System.out.printf("  B (对称-无词干): %.2f ms (%.2f μs/次)%n", 
                symNoStemTime / 1_000_000.0, symNoStemTime / 1000.0 / 1000);
            System.out.printf("  AA (非对称-有词干): %.2f ms (%.2f μs/次)%n", 
                asymWithStemTime / 1_000_000.0, asymWithStemTime / 1000.0 / 1000);
            System.out.printf("  BB (对称-有词干): %.2f ms (%.2f μs/次)%n", 
                symWithStemTime / 1_000_000.0, symWithStemTime / 1000.0 / 1000);
            
            System.out.printf("  速度比较: 非对称快%.2fx (无词干), %.2fx (有词干)%n",
                (double)symNoStemTime / asymNoStemTime,
                (double)symWithStemTime / asymWithStemTime);
        }
        
        // 知识库查询性能测试
        System.out.println("\n知识库查询性能测试:");
        String perfQuery = "测试内容 test content";
        
        // 测试混合模式
        long start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            matcher.findBestMatches(perfQuery, 5, 0.0, false);
        }
        long mixedTime = System.nanoTime() - start;
        
        // 测试纯非对称模式
        start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            matcher.findBestMatches(perfQuery, 5, 0.0, true);
        }
        long asymTime = System.nanoTime() - start;
        
        System.out.printf("混合模式（70%%AA+30%%BB）: %.2f ms/100次, %.2f ms/次%n", 
            mixedTime / 1_000_000.0, mixedTime / 1_000_000.0 / 100);
        System.out.printf("纯非对称模式（100%%AA）: %.2f ms/100次, %.2f ms/次%n", 
            asymTime / 1_000_000.0, asymTime / 1_000_000.0 / 100);
        System.out.printf("性能提升: %.2fx%n", (double)mixedTime / asymTime);
        
        // 显示缓存统计
        Map<String, Object> stats = getCacheStats();
        System.out.println("\n缓存统计信息:");
        stats.forEach((k, v) -> {
            String key = k;
            switch(k) {
                case "tokenCacheSize": key = "词元缓存大小"; break;
                case "stemCacheSize": key = "词干缓存大小"; break;
                case "maxCacheSize": key = "最大缓存容量"; break;
                case "memoryUsagePercent": key = "内存使用率"; break;
            }
            System.out.printf("  %s: %s%s%n", key, v, k.contains("Percent") ? "%" : "");
        });
        
        // 缓存命中率测试
        clearCache();
        System.out.println("\n缓存效果测试:");
        
        // 第一轮：冷启动
        start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            calculateStringJaccardSimilarity("test query " + (i % 10), "test document " + (i % 10));
        }
        long coldTime = System.nanoTime() - start;
        
        // 第二轮：缓存预热后
        start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            calculateStringJaccardSimilarity("test query " + (i % 10), "test document " + (i % 10));
        }
        long warmTime = System.nanoTime() - start;
        
        System.out.printf("冷启动耗时: %.2f ms%n", coldTime / 1_000_000.0);
        System.out.printf("缓存预热后: %.2f ms%n", warmTime / 1_000_000.0);
        System.out.printf("缓存加速比: %.2fx%n", (double)coldTime / warmTime);
    }
    
    private static void demonstrateBatchComparison() {
        System.out.println("\n=== 批量对比演示 ===");
        
        String[][] testPairs = {
            {"user login failed", "user cannot login"},
            {"system configuration", "configure system settings"},
            {"password reset", "reset user password"},
            {"application crashed", "app crashes"},
            {"creating editing saving", "create edit save"}
        };
        
        System.out.println("四种相似度计算方法的综合对比:");
        System.out.println("A: 非对称Jaccard（无词干）  B: 对称Jaccard（无词干）");
        System.out.println("AA: 非对称Jaccard（有词干） BB: 对称Jaccard（有词干）");
        System.out.println("-".repeat(100));
        
        // 统计累计耗时
        long totalAsymNoStemTime = 0;
        long totalSymNoStemTime = 0;
        long totalAsymWithStemTime = 0;
        long totalSymWithStemTime = 0;
        
        for (String[] pair : testPairs) {
            System.out.printf("\n文本对: \"%s\" <-> \"%s\"%n", pair[0], pair[1]);
            
            // 计算四种相似度并记录耗时
            long startTime = System.nanoTime();
            double asymNoStem = calculateAsymmetricSimilarityWithoutStemming(pair[0], pair[1]);
            long asymNoStemTime = System.nanoTime() - startTime;
            totalAsymNoStemTime += asymNoStemTime;
            
            startTime = System.nanoTime();
            double symNoStem = calculateSimilarityWithoutStemming(pair[0], pair[1]);
            long symNoStemTime = System.nanoTime() - startTime;
            totalSymNoStemTime += symNoStemTime;
            
            startTime = System.nanoTime();
            double asymWithStem = calculateAsymmetricStringSimilarity(pair[0], pair[1]);
            long asymWithStemTime = System.nanoTime() - startTime;
            totalAsymWithStemTime += asymWithStemTime;
            
            startTime = System.nanoTime();
            double symWithStem = calculateStringJaccardSimilarity(pair[0], pair[1]);
            long symWithStemTime = System.nanoTime() - startTime;
            totalSymWithStemTime += symWithStemTime;
            
            // 展示结果
            System.out.println("不使用词干提取:");
            System.out.printf("  A=%.4f (%.1fμs)  B=%.4f (%.1fμs)  差异(A-B)=%+.4f%n", 
                asymNoStem, asymNoStemTime/1000.0, symNoStem, symNoStemTime/1000.0, asymNoStem - symNoStem);
            System.out.println("使用词干提取:");
            System.out.printf("  AA=%.4f (%.1fμs) BB=%.4f (%.1fμs) 差异(AA-BB)=%+.4f%n", 
                asymWithStem, asymWithStemTime/1000.0, symWithStem, symWithStemTime/1000.0, asymWithStem - symWithStem);
            System.out.println("词干提取效果:");
            System.out.printf("  非对称提升(AA-A)=%+.4f  对称提升(BB-B)=%+.4f%n", 
                asymWithStem - asymNoStem, symWithStem - symNoStem);
            
            // 计算反向非对称相似度（文档到查询）
            double asymReverse = calculateAsymmetricStringSimilarity(pair[1], pair[0]);
            System.out.printf("  反向非对称相似度: %.4f%n", asymReverse);
        }
        
        // 统计汇总
        System.out.println("\n=== 统计汇总 ===");
        double totalAsymImprovement = 0;
        double totalSymImprovement = 0;
        int asymBetterCount = 0;
        int stemBetterCount = 0;
        
        for (String[] pair : testPairs) {
            double asymNoStem = calculateAsymmetricSimilarityWithoutStemming(pair[0], pair[1]);
            double symNoStem = calculateSimilarityWithoutStemming(pair[0], pair[1]);
            double asymWithStem = calculateAsymmetricStringSimilarity(pair[0], pair[1]);
            double symWithStem = calculateStringJaccardSimilarity(pair[0], pair[1]);
            
            totalAsymImprovement += (asymWithStem - asymNoStem);
            totalSymImprovement += (symWithStem - symNoStem);
            
            if (asymWithStem > symWithStem) asymBetterCount++;
            if (asymWithStem > asymNoStem || symWithStem > symNoStem) stemBetterCount++;
        }
        
        System.out.printf("平均非对称相似度提升: %.4f%n", totalAsymImprovement / testPairs.length);
        System.out.printf("平均对称相似度提升: %.4f%n", totalSymImprovement / testPairs.length);
        System.out.printf("非对称优于对称的案例: %d/%d%n", asymBetterCount, testPairs.length);
        System.out.printf("词干提取有效的案例: %d/%d%n", stemBetterCount, testPairs.length);
        
        // 性能统计
        System.out.println("\n=== 性能统计 ===");
        System.out.printf("平均耗时（微秒）:%n");
        System.out.printf("  A (非对称-无词干): %.2f μs%n", totalAsymNoStemTime / testPairs.length / 1000.0);
        System.out.printf("  B (对称-无词干): %.2f μs%n", totalSymNoStemTime / testPairs.length / 1000.0);
        System.out.printf("  AA (非对称-有词干): %.2f μs%n", totalAsymWithStemTime / testPairs.length / 1000.0);
        System.out.printf("  BB (对称-有词干): %.2f μs%n", totalSymWithStemTime / testPairs.length / 1000.0);
        
        System.out.printf("\n性能比较:%n");
        System.out.printf("  词干提取开销（非对称）: %.2fx%n", 
            (double)totalAsymWithStemTime / totalAsymNoStemTime);
        System.out.printf("  词干提取开销（对称）: %.2fx%n", 
            (double)totalSymWithStemTime / totalSymNoStemTime);
        System.out.printf("  非对称vs对称速度优势: %.2fx (无词干), %.2fx (有词干)%n",
            (double)totalSymNoStemTime / totalAsymNoStemTime,
            (double)totalSymWithStemTime / totalAsymWithStemTime);
    }
    
    private static void demonstrateMemorySafety() {
        System.out.println("\n=== 内存安全演示 ===");
        
        // 测试大量文本的缓存行为
        System.out.println("测试大量文档的缓存行为...");
        
        // 清空缓存
        clearCache();
        
        // 生成大量不同的文本
        for (int i = 0; i < MAX_CACHE_SIZE + 1000; i++) {
            String text = "文档 " + i + " 包含唯一内容 " + UUID.randomUUID();
            tokenize(text, true);
            
            if (i % 1000 == 0) {
                Map<String, Object> stats = getCacheStats();
                System.out.printf("处理 %d 个文档后 - 词元缓存大小: %d, 内存使用率: %.1f%%%n", 
                    i, stats.get("tokenCacheSize"), stats.get("memoryUsagePercent"));
            }
        }
        
        // 最终统计
        Map<String, Object> finalStats = getCacheStats();
        System.out.println("\n最终缓存统计:");
        finalStats.forEach((k, v) -> {
            String key = k;
            switch(k) {
                case "tokenCacheSize": key = "词元缓存大小"; break;
                case "stemCacheSize": key = "词干缓存大小"; break;
                case "maxCacheSize": key = "最大缓存容量"; break;
                case "memoryUsagePercent": key = "内存使用率"; break;
            }
            System.out.printf("  %s: %s%s%n", key, v, k.contains("Percent") ? "%" : "");
        });
        
        // 验证缓存没有超过限制
        System.out.printf("\n缓存大小验证: %s (不应超过 %d)%n",
            TOKEN_CACHE.size() <= MAX_CACHE_SIZE ? "通过" : "失败",
            MAX_CACHE_SIZE);
    }
}