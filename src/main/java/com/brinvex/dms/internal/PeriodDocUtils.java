package com.brinvex.dms.internal;

import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;
import java.util.function.Function;

import static java.util.Collections.emptySortedSet;
import static java.util.Comparator.comparing;

class PeriodDocUtils {

    public static <KEY> SequencedSet<KEY> findRedundantKeys(Collection<KEY> keys, Function<KEY, LocalDate> keyStartDateInclFnc, Function<KEY, LocalDate> keyEndDateInclFnc) {
        int size = keys.size();
        if (size <= 1) {
            return emptySortedSet();
        }
        List<KEY> sortedKeys = keys.stream().sorted(comparing(keyStartDateInclFnc).thenComparing(keyEndDateInclFnc)).toList();
        SequencedSet<KEY> uselessKeys = new LinkedHashSet<>();
        int prevUsefulIndex = 0;
        for (int i = 0; i < size; i++) {
            KEY midKey = sortedKeys.get(i);
            boolean useful;
            if (i == 0) {
                KEY nextKey = sortedKeys.get(i + 1);
                LocalDate midStartDateIncl = keyStartDateInclFnc.apply(midKey);
                LocalDate nextStartDateIncl = keyStartDateInclFnc.apply(nextKey);
                useful = midStartDateIncl.isBefore(nextStartDateIncl);
            } else if (i == size - 1) {
                KEY prevKey = sortedKeys.get(prevUsefulIndex);
                LocalDate midEndDateIncl = keyEndDateInclFnc.apply(midKey);
                LocalDate prevEndDateIncl = keyEndDateInclFnc.apply(prevKey);
                useful = midEndDateIncl.isAfter(prevEndDateIncl);
            } else {
                KEY prevKey = sortedKeys.get(prevUsefulIndex);
                KEY nextKey = sortedKeys.get(i + 1);
                LocalDate prevEndDateExcl = keyEndDateInclFnc.apply(prevKey).plusDays(1);
                LocalDate nextStartDateIncl = keyStartDateInclFnc.apply(nextKey);
                boolean neighborsContinuous = !prevEndDateExcl.isBefore(nextStartDateIncl);
                if (neighborsContinuous) {
                    boolean inside = !keyStartDateInclFnc.apply(midKey).isBefore(keyStartDateInclFnc.apply(prevKey))
                                     && !keyEndDateInclFnc.apply(midKey).isAfter(keyEndDateInclFnc.apply(nextKey));
                    useful = !inside;
                } else {
                    boolean insidePrev = !keyStartDateInclFnc.apply(midKey).isBefore(keyStartDateInclFnc.apply(prevKey))
                                         && !keyEndDateInclFnc.apply(midKey).isAfter(keyEndDateInclFnc.apply(prevKey));
                    boolean insideNext = !keyStartDateInclFnc.apply(midKey).isBefore(keyStartDateInclFnc.apply(nextKey))
                                         && !keyEndDateInclFnc.apply(midKey).isAfter(keyEndDateInclFnc.apply(nextKey));
                    useful = !insidePrev && !insideNext;
                }
            }
            if (!useful) {
                uselessKeys.add(midKey);
            } else {
                prevUsefulIndex = i;
            }
        }
        return uselessKeys;
    }

}
