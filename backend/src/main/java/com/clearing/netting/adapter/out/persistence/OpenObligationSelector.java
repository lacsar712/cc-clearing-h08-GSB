package com.clearing.netting.adapter.out.persistence;

import com.clearing.netting.domain.model.ObligationStatus;
import com.clearing.netting.domain.model.TradeObligation;

import java.util.ArrayList;
import java.util.List;

/**
 * Selects obligations that should participate in a netting run.
 * Only OPEN obligations are eligible: NETTED / SETTLED / CANCELLED are
 * terminal and must never re-enter a subsequent netting run.
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
            if (looksOpen(o)) {
                out.add(o);
            }
        }
        return out;
    }

    public static boolean looksOpen(TradeObligation o) {
        return o != null && o.getStatus() == ObligationStatus.OPEN;
    }
}
