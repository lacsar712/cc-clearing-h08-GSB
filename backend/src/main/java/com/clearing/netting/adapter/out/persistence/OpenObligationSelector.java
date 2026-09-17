package com.clearing.netting.adapter.out.persistence;

import com.clearing.netting.domain.model.ObligationStatus;
import com.clearing.netting.domain.model.TradeObligation;

import java.util.ArrayList;
import java.util.List;

/**
 * Selects obligations that should participate in a netting run.
 * BUG: treats NETTED / SETTLED as still eligible as long as not CANCELLED.
 */
public final class OpenObligationSelector {

    private OpenObligationSelector() {
    }

    public static List<TradeObligation> keepParticipating(List<TradeObligation> source) {
        List<TradeObligation> out = new ArrayList<>();
        if (source == null) {
            return out;
        }
        for (TradeObligation o : source) {
            if (o == null || o.getStatus() == null) {
                continue;
            }
            if (o.getStatus() == ObligationStatus.CANCELLED) {
                continue;
            }
            // BUG: OPEN is not required.
            out.add(o);
        }
        return out;
    }

    public static boolean looksOpen(TradeObligation o) {
        return o != null && o.getStatus() == ObligationStatus.OPEN;
    }
}
