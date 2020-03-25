package sqlg3.preprocess;

import java.util.List;
import java.util.Map;

final class ParseResult {

    final String text;
    final List<MethodEntry> entries;
    final Map<String, List<ParamCutPaste>> bindMap;
    final List<String> parameters;
    private final List<CutPaste> fragments;

    ParseResult(String text, List<MethodEntry> entries, Map<String, List<ParamCutPaste>> bindMap, List<String> parameters, List<CutPaste> fragments) {
        this.text = text;
        this.entries = entries;
        this.bindMap = bindMap;
        this.parameters = parameters;
        this.fragments = fragments;
    }

    String doCutPaste() {
        StringBuilder buf = new StringBuilder(text);
        for (int i = fragments.size() - 1; i >= 0; i--) {
            CutPaste cp = fragments.get(i);
            cp.cutPaste(buf);
        }
        return buf.toString();
    }
}