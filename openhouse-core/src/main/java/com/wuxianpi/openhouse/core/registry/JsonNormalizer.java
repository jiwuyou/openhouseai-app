package com.wuxianpi.openhouse.core.registry;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

final class JsonNormalizer {
    private JsonNormalizer() {}

    static String normalizeObject(JSONObject object) throws JSONException {
        return normalizeValue(object).toString();
    }

    private static Object normalizeValue(Object value) throws JSONException {
        if (value instanceof JSONObject) {
            JSONObject input = (JSONObject) value;
            List<String> keys = new ArrayList<>();
            Iterator<String> iterator = input.keys();
            while (iterator.hasNext()) keys.add(iterator.next());
            Collections.sort(keys);
            JSONObject output = new JSONObject();
            for (String key : keys) output.put(key, normalizeValue(input.get(key)));
            return output;
        }
        if (value instanceof JSONArray) {
            JSONArray input = (JSONArray) value;
            JSONArray output = new JSONArray();
            for (int i = 0; i < input.length(); i++) output.put(normalizeValue(input.get(i)));
            return output;
        }
        return value;
    }
}
