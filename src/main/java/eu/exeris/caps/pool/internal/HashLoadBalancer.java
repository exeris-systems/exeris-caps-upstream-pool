package eu.exeris.caps.pool.internal;

import eu.exeris.caps.pool.api.LoadBalancer;
import eu.exeris.caps.pool.api.UpstreamCluster;
import eu.exeris.caps.pool.api.UpstreamHost;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Key-affine {@link LoadBalancer}: the same key selects the same host while that host stays available.
 *
 * <p>Uses rendezvous (highest-random-weight) hashing: each candidate host scores the key against a seed
 * derived from its endpoint authority, and the highest score wins. When a host leaves the candidate set,
 * only the keys that had selected it move; every other key keeps its host.
 *
 * @implSpec Thread-safe. The key is supplied by the caller, for example from
 *           {@code UpstreamPools.addressKey(byte[])}; this class never reads a request. Selection is an
 *           index scan with arithmetic only, so it takes no lock and allocates nothing. Without a key,
 *           {@link #select(UpstreamCluster)} rotates round-robin over the candidates.
 * @since 0.1.0
 */
public final class HashLoadBalancer implements LoadBalancer {

    private final AtomicLong sequence = new AtomicLong(0);

    @Override
    public UpstreamHost select(UpstreamCluster cluster) {
        List<UpstreamHost> all = cluster.allHosts();
        boolean anyAvailable = Candidates.anyAvailable(all);
        int count = Candidates.count(all, anyAvailable);
        if (count == 0) {
            return null;
        }
        return Candidates.nth(all, anyAvailable, Math.floorMod(sequence.getAndIncrement(), count));
    }

    @Override
    public UpstreamHost select(UpstreamCluster cluster, long hashKey) {
        List<UpstreamHost> all = cluster.allHosts();
        boolean anyAvailable = Candidates.anyAvailable(all);
        UpstreamHost best = null;
        long bestScore = 0L;
        for (int i = 0, n = all.size(); i < n; i++) {
            UpstreamHost host = all.get(i);
            if (!Candidates.isCandidate(host, anyAvailable)) {
                continue;
            }
            long score = mix(hashKey ^ mix(host.endpoint().authority().hashCode()));
            if (best == null || Long.compareUnsigned(score, bestScore) > 0) {
                best = host;
                bestScore = score;
            }
        }
        return best;
    }

    /** The SplitMix64 finalizer: a bijective avalanche over 64 bits. */
    static long mix(long value) {
        long z = value + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
