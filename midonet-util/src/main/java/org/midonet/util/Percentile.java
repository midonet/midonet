package org.midonet.util;

import java.io.Serializable;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 
 * @author Dan Mihai Dumitriu
 * 
 *         P^2 Algorithm from Raj Jain
 * 
 */
public class Percentile implements Cloneable, Serializable {
    private static final long serialVersionUID = -4591386581109141524L;
    private static Logger logger_ = LoggerFactory.getLogger(Percentile.class);

    private final static double PERCENTILES_[] = { 0.00001, 0.0001, 0.001, 0.01, 0.02, 0.03, 0.04, 0.05, 0.06, 0.07,
            0.08, 0.09, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 0.91, 0.92, 0.93, 0.94, 0.95, 0.96, 0.97, 0.98,
            0.99, 0.999, 0.9999, 0.99999 };

    private final static int I = 2 * PERCENTILES_.length + 3; // 69

    private double q[] = new double[I]; /* heights */
    private int n[] = new int[I]; /* actual positions */
    private double d[] = new double[I]; /* desired positions */
    private double f[] = new double[I]; /* increments */

    private int count_;

    private double min_;
    private double max_;

    public void clear() {
        q = new double[I];
        n = new int[I];
        d = new double[I];
        f = new double[I];

        count_ = 0;
        min_ = Double.MAX_VALUE;
        max_ = Double.MIN_VALUE;
    }

    public Percentile copy() {
        Percentile stats = new Percentile();
        stats.q = q;
        stats.n = n;
        stats.d = d;
        stats.f = f;

        stats.count_ = count_;
        stats.min_ = min_;
        stats.max_ = max_;

        return stats;
    }

    public void sample(double v) {
        assert (!Double.isNaN(v));

        // First, do the min and max
        if (0 == count_) {
            min_ = max_ = v;
        } else {
            if (min_ > v) {
                min_ = v;
            }

            if (max_ < v) {
                max_ = v;
            }
        }

        // If count is less than 2M+3, we're still in an init phase
        if (count_ < I) {
            q[count_++] = v; // record

            // Once count is 2M+3, initialize the positions
            if (I == count_) {
                f[0] = 0;
                f[I - 1] = 1;

                for (int i = 2; i < I - 1; i += 2) {
                    f[i] = PERCENTILES_[(i - 1) / 2];
                }

                for (int i = 1; i < I - 1; i += 2) {
                    f[i] = 0.5 * (f[i - 1] + f[i + 1]);
                }

                for (int i = 0; i < I; i += 1) {
                    n[i] = i;
                    d[i] = 1 + 2 * (PERCENTILES_.length + 1) * f[i];
                }
            }

            // Sort
            Arrays.sort(q, 0, count_);

            return;
        }

        // Find cell k
        int k;
        if (v < q[0]) {
            k = 0;
            q[0] = v;
        } else if (v >= q[I - 1]) {
            k = I - 2;
            q[I - 1] = v;
        } else {
            for (k = 0; (k < I - 1) && (v >= q[k + 1]); k += 1) {
                assert (q[k] <= v);
            }
        }

        // Increase the desired positions of all markers
        // Notice that we swap the order of the operations here
        for (int i = 1; i <= k; i += 1) {
            d[i] += f[i];
        }

        // Increase actual positions of markers k+1, ..., 2m+3
        for (int i = k + 1; i < I; i += 1) {
            d[i] += f[i];
            n[i] += 1;
        }

        // Adjust heights and actual positions of markers 2..., 2m+2
        for (int i = 1; i + 1 < I; i += 1) {
            double dd = d[i] - n[i];
            int dp = n[i + 1] - n[i];
            int neg_dm = n[i] - n[i - 1];

            if (dd >= 1 && dp > 1) {
                double qp = (q[i + 1] - q[i]) / dp;
                double qm = (q[i] - q[i - 1]) / neg_dm;
                double qt = q[i] + ((1 + neg_dm) * qp + (dp - 1) * qm) / (dp + neg_dm);

                if (q[i - 1] < qt && qt < q[i + 1]) {
                    q[i] = qt;
                } else {
                    q[i] += qp;
                }

                n[i] += 1;
            } else if (dd <= -1 && neg_dm > 1) {
                double qp = (q[i + 1] - q[i]) / dp;
                double qm = (q[i] - q[i - 1]) / neg_dm;
                double qt = q[i] - ((1 + dp) * qm - (1 - neg_dm) * qp) / (dp + neg_dm);

                if (q[i - 1] < qt && qt < q[i + 1]) {
                    q[i] = qt;
                } else {
                    q[i] -= qm;
                }

                n[i] -= 1;
            }

            assert (q[i] <= max_);
            assert (q[i] >= min_);
            assert (q[i] >= q[i - 1]);
        }

        // Increment the counter and add the element to the total
        count_ += 1;
    }

    private boolean eq_dbl(double a, double b) {
        a -= b;
        return (-0.00001 <= a && a <= 0.00001);

        // return a == b;
    }

    public double getPercentile(double pct) {
        // Deal with a corner case first
        if (count_ < I) {
            int i;

            for (i = 0; i < count_ && (i + 1) < (pct * count_); i += 1)
                ;

            if (0 == i)
                return q[0];

            return q[i - 1];
        }

        if (pct < 0) {
            pct = 0;
        }

        if (pct > 1) {
            pct = 1;
        }

        if (pct < PERCENTILES_[0]) {
            return min_;
        }

        if (pct > PERCENTILES_[PERCENTILES_.length - 1]) {
            return max_;
        }

        for (int i = 0; i < PERCENTILES_.length; i += 1) {
            if (eq_dbl(pct, PERCENTILES_[i])) {
                return q[2 * i + 2];
            }

            if (pct < PERCENTILES_[i]) {
                return q[2 * i + 2] * ((pct - PERCENTILES_[i - 1]) / (PERCENTILES_[i] - PERCENTILES_[i - 1]))
                        + q[2 * i] * ((PERCENTILES_[i] - pct) / (PERCENTILES_[i] - PERCENTILES_[i - 1]));
            }
        }

        // should never get here
        return -1;
    }

    /*
     * Merge other into this.
     * 
     * TODO: could this be slow?
     * 
     * Quoting Stefan:
     * 
     * "This is a total hack. We're basically creating a fake input stream for
     * the state passed in and we're inserting this stream in the old state.
     * Yuck."
     */
    public void merge(final Percentile source) {
        if (source.count_ < I) {
            this.sample(source.min_);

            for (int i = 0; i < source.count_; i++) {
                this.sample(source.q[i]);
            }

            this.sample(source.max_);
        } else {
            int inserted = 0;

            this.sample(source.min_);
            this.sample(source.max_);
            inserted += 2;

            for (int i = 0; i < PERCENTILES_.length; i++) {
                int step;
                double increment;
                double previous, current;

                if (i == 0) {
                    previous = source.min_;
                    step = (int) ((double) source.count_ * PERCENTILES_[i]);
                } else if (i == I - 1) {
                    previous = source.getPercentile(PERCENTILES_[i - 1]);
                    step = source.count_ - inserted;
                } else {
                    previous = source.getPercentile(PERCENTILES_[i - 1]);
                    step = (int) ((double) source.count_ * (PERCENTILES_[i] - PERCENTILES_[i - 1]));
                }

                current = source.getPercentile(PERCENTILES_[i]);
                increment = (current - previous) / step;

                for (; step > 0; step--) {
                    previous += increment;
                    this.sample(previous);
                    inserted++;

                    if (inserted == count_) {
                        return;
                    }
                }
            }
        }
    }

}
