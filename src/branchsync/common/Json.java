package branchsync.common;
import java.lang.reflect.*;
import java.util.*;

/** Small output-only JSON serializer. HTTP inputs use URL-encoded forms. */
public final class Json {
    private Json() {}
    public static String stringify(Object o) {
        if (o == null) return "null";
        if (o instanceof String s) return quote(s);
        if (o instanceof Number || o instanceof Boolean) return o.toString();
        if (o instanceof Map<?,?> m) {
            List<String> out = new ArrayList<>();
            m.forEach((k,v)->out.add(quote(k.toString())+":"+stringify(v)));
            return "{"+String.join(",",out)+"}";
        }
        if (o instanceof Iterable<?> values) {
            List<String> out = new ArrayList<>();
            values.forEach(v->out.add(stringify(v)));
            return "["+String.join(",",out)+"]";
        }
        Map<String,Object> fields = new LinkedHashMap<>();
        try {
            if (o.getClass().isRecord()) {
                for (RecordComponent r:o.getClass().getRecordComponents()) fields.put(r.getName(),r.getAccessor().invoke(o));
            } else {
                for (Field f:o.getClass().getFields()) if (!Modifier.isStatic(f.getModifiers())) fields.put(f.getName(),f.get(o));
            }
        } catch (ReflectiveOperationException e) { throw new IllegalArgumentException(e); }
        return stringify(fields);
    }
    public static String canonical(Object o){return stringify(ordered(o));}
    private static Object ordered(Object o){
        if(o==null||o instanceof String||o instanceof Number||o instanceof Boolean)return o;
        if(o instanceof Map<?,?> m){Map<String,Object> out=new TreeMap<>();m.forEach((k,v)->out.put(k.toString(),ordered(v)));return out;}
        if(o instanceof Iterable<?> list){List<Object> out=new ArrayList<>();list.forEach(v->out.add(ordered(v)));return out;}
        Map<String,Object> out=new TreeMap<>();
        try{if(o.getClass().isRecord()){for(RecordComponent r:o.getClass().getRecordComponents())out.put(r.getName(),ordered(r.getAccessor().invoke(o)));}
            else for(Field f:o.getClass().getFields())if(!Modifier.isStatic(f.getModifiers()))out.put(f.getName(),ordered(f.get(o)));
        }catch(ReflectiveOperationException e){throw new IllegalArgumentException(e);}return out;
    }
    private static String quote(String s) {
        StringBuilder b=new StringBuilder("\"");
        for (char c:s.toCharArray()) switch(c) {
            case '"' -> b.append("\\\""); case '\\' -> b.append("\\\\");
            case '\n' -> b.append("\\n"); case '\r' -> b.append("\\r"); case '\t' -> b.append("\\t");
            default -> { if(c<32) b.append(String.format("\\u%04x",(int)c)); else b.append(c); }
        }
        return b.append('"').toString();
    }
}
