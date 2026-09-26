package fr.an.huggingface.util;

import jakarta.annotation.Nonnull;
import lombok.val;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/**
 * utility class methods for List, stream
 */
public class LsUtil {

    private LsUtil() {}

    public static <T> List<T> emptyIfNull(List<T> src) {
        return (src == null)? new ArrayList<>(0) : src;
    }

    public static <TSrc,TDest>List<TDest> mapOrNull(Collection<TSrc> src, Function<TSrc,TDest> mapFunc) {
        if (src == null) {
            return null;
        }
        return map(src, mapFunc);
    }

    public static <TSrc,TDest>List<TDest> map(@Nonnull Collection<TSrc> src, Function<TSrc,TDest> mapFunc) {
        // return src.stream().map(mapFunc).collect(Collectors.toList());
        val res = new ArrayList<TDest>(src.size());
        for(val item: src) {
            res.add(mapFunc.apply(item));
        }
        return res;
    }

}
