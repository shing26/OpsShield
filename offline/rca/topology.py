"""两个系统的调用图（caller → callee）。

来源：Google microservices-demo 与 Sock Shop 的服务依赖。只收录对本切片有用的边；
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

SOCK_SHOP_CALLS: dict[str, frozenset[str]] = {
    "front-end": frozenset({"carts", "catalogue", "orders", "user"}),
    "orders": frozenset({"carts", "payment", "shipping", "user"}),
}

GRAPHS = {
    "online-boutique": ONLINE_BOUTIQUE_CALLS,
    "sock-shop": SOCK_SHOP_CALLS,
}


def callees(service: str, system: str = "online-boutique") -> frozenset[str]:
    graph = GRAPHS.get(system, {})
    return frozenset(c for c in graph.get(service, frozenset()) if c != service)
