package eu.exeris.caps.pool.internal;

import eu.exeris.caps.pool.api.LoadBalancer;
import eu.exeris.caps.pool.api.UpstreamCluster;
import eu.exeris.caps.pool.api.UpstreamHost;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Smooth weighted round-robin {@link LoadBalancer}: each candidate host is selected in proportion to its
 * endpoint weight, with the selections of a heavy host spread through the cycle rather than bunched.
 *
 * <p>The cycle is computed ahead of time. The smooth weighted algorithm (add each weight to a running
 * credit, pick the largest credit, subtract the total) is replayed once over the candidate set to fill
 * a schedule of host indexes; a selection then reads {@code schedule[sequence mod length]}. The schedule is
 * rebuilt only when the candidate set changes. Weights are divided by their greatest common divisor and, when
 * the cycle would still exceed {@value #MAX_CYCLE} slots, scaled down proportionally (each weight stays at
 * least 1), so very large weights are honoured approximately rather than exactly.
 *
 * @implSpec Thread-safe. In steady state a selection is one {@link AtomicLong} increment, a scan comparing
 *           host availability with the schedule's candidate set, and an array read: no lock, no retry and no
 *           allocation. A change of the candidate set allocates a new schedule once, outside steady state;
 *           concurrent rebuilds publish equivalent schedules and the last write wins. The {@code hashKey}
 *           of {@link #select(UpstreamCluster, long)} is ignored.
 * @since 0.1.0
 */
public final class SmoothWeightedRoundRobinLoadBalancer implements LoadBalancer {

    /** Upper bound on the length of a computed cycle. */
    static final int MAX_CYCLE = 4096;

    private final AtomicLong sequence = new AtomicLong(0);
    private volatile Schedule schedule;

    @Override
    public UpstreamHost select(UpstreamCluster cluster) {
        List<UpstreamHost> all = cluster.allHosts();
        int size = all.size();
        if (size == 0) {
            return null;
        }
        boolean anyAvailable = Candidates.anyAvailable(all);
        Schedule current = schedule;
        if (current == null || !current.matches(all, anyAvailable)) {
            current = Schedule.build(all, anyAvailable);
            schedule = current;
        }
        int[] order = current.order;
        if (order.length == 0) {
            return null;
        }
        return all.get(order[Math.floorMod(sequence.getAndIncrement(), order.length)]);
    }

    @Override
    public UpstreamHost select(UpstreamCluster cluster, long hashKey) {
        return select(cluster);
    }

    /** The candidate set a cycle was computed for, and the cycle as host indexes. */
    private static final class Schedule {
        private final boolean[] candidates;
        final int[] order;

        private Schedule(boolean[] candidates, int[] order) {
            this.candidates = candidates;
            this.order = order;
        }

        boolean matches(List<UpstreamHost> hosts, boolean anyAvailable) {
            if (candidates.length != hosts.size()) {
                return false;
            }
            for (int i = 0; i < candidates.length; i++) {
                if (Candidates.isCandidate(hosts.get(i), anyAvailable) != candidates[i]) {
                    return false;
                }
            }
            return true;
        }

        static Schedule build(List<UpstreamHost> hosts, boolean anyAvailable) {
            int size = hosts.size();
            boolean[] candidates = new boolean[size];
            long[] weights = new long[size];
            long divisor = 0;
            long total = 0;
            for (int i = 0; i < size; i++) {
                UpstreamHost host = hosts.get(i);
                if (Candidates.isCandidate(host, anyAvailable)) {
                    candidates[i] = true;
                    weights[i] = host.endpoint().weight();
                    divisor = gcd(divisor, weights[i]);
                }
            }
            for (int i = 0; i < size; i++) {
                if (candidates[i]) {
                    weights[i] /= divisor;
                    total += weights[i];
                }
            }
            if (total > MAX_CYCLE) {
                long scaledTotal = 0;
                for (int i = 0; i < size; i++) {
                    if (candidates[i]) {
                        weights[i] = Math.max(1L, weights[i] * MAX_CYCLE / total);
                        scaledTotal += weights[i];
                    }
                }
                total = scaledTotal;
            }
            if (total == 0) {
                return new Schedule(candidates, new int[0]);
            }
            int[] order = new int[(int) total];
            long[] credit = new long[size];
            for (int step = 0; step < order.length; step++) {
                int best = -1;
                for (int i = 0; i < size; i++) {
                    if (!candidates[i]) {
                        continue;
                    }
                    credit[i] += weights[i];
                    if (best < 0 || credit[i] > credit[best]) {
                        best = i;
                    }
                }
                credit[best] -= total;
                order[step] = best;
            }
            return new Schedule(candidates, order);
        }

        private static long gcd(long a, long b) {
            while (b != 0) {
                long t = a % b;
                a = b;
                b = t;
            }
            return a;
        }
    }
}
