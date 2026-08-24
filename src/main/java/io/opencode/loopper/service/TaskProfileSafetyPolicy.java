package io.opencode.loopper.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Distinguishes requested external mutations from business-domain publication language. */
final class TaskProfileSafetyPolicy {
    private static final Pattern DIRECT_UNSAFE_ACTION = Pattern.compile(
            "删除文件|\\brm\\s+|启动服务|停止服务|重启服务|操作服务|推送|\\bgit\\s+push\\b|git 提交|提交代码|创建提交|\\bcommit\\b");
    private static final Pattern EXTERNAL_WRITE = Pattern.compile(
            "(?:写入|修改|更新|删除|创建|新增|上传|同步|发送).{0,12}(?:外部系统|外部应用)"
                    + "|(?:外部系统|外部应用).{0,12}(?:写入|修改|更新|删除|创建|新增|上传|同步|发送)");
    private static final Pattern PUBLICATION = Pattern.compile(
            "发布(?!器|者|[-/]?订阅)|publish(?:ing)?\\b");
    private static final Pattern NEGATION = Pattern.compile(
            "(?:不得|禁止|不要|无需|避免|不能|不会|严禁|拒绝|防止|不)[^。；;，,\\n\\r]{0,10}$");
    private static final Pattern RELEASE_TARGET = Pattern.compile(
            "(?:发布|publish(?:ing)?)(?!包含|携带|带有).{0,8}"
                    + "(?:新版本|版本|制品|构建产物|镜像|安装包|软件包|release\\b|artifact\\b|image\\b|package\\b)"
                    + "|(?:版本|制品|构建产物|镜像|安装包|软件包|release\\b|artifact\\b|image\\b|package\\b)"
                    + ".{0,8}(?:发布|publish(?:ing)?)"
                    + "|(?:发布|publish(?:ing)?).{0,12}(?:生产环境|测试环境|预发环境|线上环境|github releases?)");
    private static final Pattern BUSINESS_PUBLICATION_TARGET = Pattern.compile(
            "(?:领域|业务|状态|链路|生命周期|开始|成功|失败|补偿)?事件|消息|通知|信号|指标|发布订阅"
                    + "|(?:domain\\s+|business\\s+)?events?\\b|messages?\\b|notifications?\\b|signals?\\b|metrics?\\b|pub(?:lish)?[/-]?sub\\b"
                    + "|\\bchain_(?:started|succeeded|failed|compensated)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern BUSINESS_PUBLICATION_CONTEXT = Pattern.compile(
            "(?:链路|节点|调用|执行|轨迹|状态机|领域|业务|补偿|异常|失败|成功|开始).{0,24}(?:发布|publish)"
                    + "|(?:发布|publish).{0,24}(?:可观测|监听|订阅|事件|消息|通知|信号|指标)"
                    + "|进程内.{0,8}(?:发布|publish)"
                    + "|(?:started|succeeded|failed|compensated)\\s*(?:重复|再次|重新|二次)?\\s*(?:发布|publish)"
                    + "|(?:发布|publish)\\s*(?:started|succeeded|failed|compensated)"
                    + "|监听器.{0,8}注册[、,/]\\s*(?:发布|publish).{0,8}(?:类型)?分发"
                    + "|(?:发布|publish).{0,8}按(?:事件)?类型分发", Pattern.CASE_INSENSITIVE);
    private static final Pattern REPEATED_PUBLICATION_PREFIX = Pattern.compile(
            "(?:重复|再次|重新|二次|仅|只)[^。；;，,\\n\\r]{0,4}$");

    boolean requestsUnsafeOperation(String requirement) {
        String text = requirement == null ? "" : requirement;
        if (hasPositiveMatch(text, DIRECT_UNSAFE_ACTION) || hasPositiveMatch(text, EXTERNAL_WRITE)) return true;
        return requestsExternalPublication(text);
    }

    private static boolean requestsExternalPublication(String text) {
        for (String phrase : List.of("上线部署", "执行 release", "create release", "发版")) {
            int start = text.indexOf(phrase);
            if (start >= 0 && !isNegated(text, start)) return true;
        }
        var publications = new ArrayList<int[]>();
        var matcher = PUBLICATION.matcher(text);
        while (matcher.find()) publications.add(new int[] {matcher.start(), matcher.end()});
        for (int index = 0; index < publications.size(); index++) {
            int start = publications.get(index)[0];
            if (isNegated(text, start)) continue;
            int sentenceStart = publicationSegmentStart(text, start);
            int sentenceEnd = publicationSegmentEnd(text, publications.get(index)[1]);
            int nextPublication = index + 1 < publications.size() ? publications.get(index + 1)[0] : text.length();
            int spanEnd = Math.min(sentenceEnd, nextPublication);
            int spanStart = publicationContextStart(text, publications, index, sentenceStart, start);
            String span = text.substring(spanStart, spanEnd);
            if (RELEASE_TARGET.matcher(span).find()) return true;
            if (BUSINESS_PUBLICATION_TARGET.matcher(span).find()
                    || BUSINESS_PUBLICATION_CONTEXT.matcher(span).find()
                    || containsAny(span, "发布边界", "发布能力")) continue;
            if (repeatsBusinessPublication(text, start, sentenceStart)) continue;
            return true;
        }
        return false;
    }

    private static int publicationContextStart(
            String text, List<int[]> publications, int index, int sentenceStart, int start) {
        int contextStart = Math.max(sentenceStart, start - 16);
        if (index == 0 || publications.get(index - 1)[0] < sentenceStart) return contextStart;
        int previousEnd = publications.get(index - 1)[1];
        for (int cursor = start - 1; cursor >= previousEnd; cursor--) {
            char value = text.charAt(cursor);
            if (value == '，' || value == ',' || value == '、') return Math.max(contextStart, cursor + 1);
        }
        return start;
    }

    private static boolean repeatsBusinessPublication(String text, int start, int sentenceStart) {
        String prefix = text.substring(Math.max(sentenceStart, start - 12), start);
        if (!REPEATED_PUBLICATION_PREFIX.matcher(prefix).find()) return false;
        String prior = text.substring(sentenceStart, start);
        return PUBLICATION.matcher(prior).find()
                && (BUSINESS_PUBLICATION_TARGET.matcher(prior).find()
                || BUSINESS_PUBLICATION_CONTEXT.matcher(prior).find()
                || containsAny(prior, "发布边界", "发布能力"));
    }

    private static boolean hasPositiveMatch(String text, Pattern operation) {
        var matcher = operation.matcher(text);
        while (matcher.find()) if (!isNegated(text, matcher.start())) return true;
        return false;
    }

    private static boolean isNegated(String text, int actionStart) {
        int scopeStart = clauseStart(text, actionStart);
        for (String contrast : List.of("但是", "不过", "然而", "但", "却")) {
            int found = text.lastIndexOf(contrast, actionStart);
            if (found >= scopeStart) scopeStart = Math.max(scopeStart, found + contrast.length());
        }
        String prefix = text.substring(scopeStart, actionStart).stripTrailing();
        return NEGATION.matcher(prefix).find();
    }

    private static int clauseStart(String text, int offset) {
        int start = 0;
        for (int index = offset - 1; index >= 0; index--) {
            char value = text.charAt(index);
            if (isPublicationBoundary(value) || value == '，' || value == ',') return index + 1;
        }
        return start;
    }

    private static int publicationSegmentStart(String text, int offset) {
        for (int index = offset - 1; index >= 0; index--) {
            if (isPublicationBoundary(text.charAt(index))) return index + 1;
        }
        return 0;
    }

    private static int publicationSegmentEnd(String text, int offset) {
        for (int index = offset; index < text.length(); index++) {
            if (isPublicationBoundary(text.charAt(index))) return index;
        }
        return text.length();
    }

    private static boolean isPublicationBoundary(char value) {
        return value == '。' || value == '；' || value == ';' || value == '\n' || value == '\r';
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) if (text.contains(needle)) return true;
        return false;
    }
}
