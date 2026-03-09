// Boost.Math chi-squared shim → lightweight implementation
// Provides chi_squared distribution and quantile function
// using Wilson-Hilferty approximation for the quantile (inverse CDF)
#pragma once
#include <cmath>

namespace boost {
namespace math {

class chi_squared {
public:
    explicit chi_squared(double df) : df_(df) {}
    double degrees_of_freedom() const { return df_; }
private:
    double df_;
};

// Inverse normal CDF (rational approximation, Abramowitz & Stegun 26.2.23)
namespace detail {
inline double inv_normal_cdf(double p) {
    // Rational approximation for 0 < p < 1
    if (p <= 0.0) return -1e30;
    if (p >= 1.0) return 1e30;
    if (p < 0.5) return -inv_normal_cdf(1.0 - p);

    double t = std::sqrt(-2.0 * std::log(1.0 - p));
    // Coefficients for rational approximation
    double c0 = 2.515517, c1 = 0.802853, c2 = 0.010328;
    double d1 = 1.432788, d2 = 0.189269, d3 = 0.001308;
    return t - (c0 + c1 * t + c2 * t * t) / (1.0 + d1 * t + d2 * t * t + d3 * t * t * t);
}
} // namespace detail

// Chi-squared quantile via Wilson-Hilferty approximation
// For df >= 1, this gives ~3 significant digits of accuracy
inline double quantile(const chi_squared &dist, double p) {
    double df = dist.degrees_of_freedom();
    if (df <= 0) return 0.0;

    // Wilson-Hilferty: chi2_p ≈ df * (1 - 2/(9*df) + z_p * sqrt(2/(9*df)))^3
    double z = detail::inv_normal_cdf(p);
    double t = 2.0 / (9.0 * df);
    double x = 1.0 - t + z * std::sqrt(t);
    if (x <= 0) x = 0.001; // clamp for very small df
    return df * x * x * x;
}

} // namespace math
} // namespace boost
