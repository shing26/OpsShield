"""Online Boutique 调用图（caller → callee）。

来源：Google microservices-demo 的服务依赖。只收录对本切片有用的边；
未列出的服务视为叶子。拓扑只用于「调用方 SLI 异常可能来自被调方」的扣减，
不参与特征构造。
"""
from __future__ import annotations

ONLINE_BOUTIQUE_CALLS: dict[str, frozenset[str]] = {
    "frontend": frozenset({
        "adservice",
        "cartservice",
        "checkoutservice",
        "currencyservice",
        "productcatalogservice",
        "recommendationservice",
        "shippingservice",
    }),
    "checkoutservice": frozenset({
        "cartservice",
        "currencyservice",
        "paymentservice",
        "productcatalogservice",
        "shippingservice",
        "emailservice",
    }),
    "recommendationservice": frozenset({"productcatalogservice"}),
    "cartservice": frozenset({"redis"}),
}


def callees(service: str) -> frozenset[str]:
    return ONLINE_BOUTIQUE_CALLS.get(service, frozenset())
