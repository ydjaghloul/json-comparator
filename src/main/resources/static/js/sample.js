/* Generated from the real comparator output; used by "Try the sample". */
window.SAMPLE = {
 "report": {
  "summary": {
   "total": 4,
   "identical": 1,
   "different": 2,
   "errors": 1,
   "typeMatching": "STRICT",
   "nullMatching": "STRICT",
   "orderStrict": false
  },
  "results": [
   {
    "path": "/customers/42/orders",
    "status": "DIFFERENT",
    "summary": "11 differences",
    "differenceCount": 11,
    "byType": {
     "VALUE_MISMATCH": 5,
     "MISSING_IN_TARGET": 3,
     "MISSING_IN_SOURCE": 3
    },
    "differences": [
     {
      "jsonPath": "$.total",
      "type": "VALUE_MISMATCH",
      "source": 100,
      "target": 105,
      "message": "$.total: source=100, target=105"
     },
     {
      "jsonPath": "$.generatedBy",
      "type": "VALUE_MISMATCH",
      "source": "v1",
      "target": "v2",
      "message": "$.generatedBy: source=\"v1\", target=\"v2\""
     },
     {
      "jsonPath": "$.customer.email",
      "type": "VALUE_MISMATCH",
      "source": null,
      "target": "ada@example.com",
      "message": "$.customer.email: source=null, target=\"ada@example.com\""
     },
     {
      "jsonPath": "$.customer.address.city",
      "type": "VALUE_MISMATCH",
      "source": "London",
      "target": "Paris",
      "message": "$.customer.address.city: source=\"London\", target=\"Paris\""
     },
     {
      "jsonPath": "$.tags[2]",
      "type": "MISSING_IN_TARGET",
      "source": "new",
      "target": null,
      "message": "$.tags[2]: element \"new\" is in source but has no match in target"
     },
     {
      "jsonPath": "$.tags[2]",
      "type": "MISSING_IN_SOURCE",
      "source": null,
      "target": "beta",
      "message": "$.tags[2]: element \"beta\" is in target but has no match in source"
     },
     {
      "jsonPath": "$.items[1].qty",
      "targetPath": "$.items[0].qty",
      "location": "$.items[sku=B-200].qty",
      "type": "VALUE_MISMATCH",
      "source": 2,
      "target": 3,
      "message": "$.items[sku=B-200].qty: source=2, target=3"
     },
     {
      "jsonPath": "$.items[1].discount",
      "location": "$.items[sku=B-200].discount",
      "type": "MISSING_IN_TARGET",
      "source": 5,
      "target": null,
      "message": "$.items[sku=B-200].discount: present in source (5), missing in target"
     },
     {
      "jsonPath": "$.items[2]",
      "location": "$.items[sku=C-300]",
      "type": "MISSING_IN_TARGET",
      "source": {
       "sku": "C-300",
       "name": "Cable",
       "qty": 3,
       "price": 4
      },
      "target": null,
      "message": "$.items[sku=C-300]: element {\"sku\":\"C-300\",\"name\":\"Cable\",\"qty\":3,\"price\":4} is in source but has no match in target"
     },
     {
      "jsonPath": "$.items[2]",
      "location": "$.items[sku=D-400]",
      "type": "MISSING_IN_SOURCE",
      "source": null,
      "target": {
       "sku": "D-400",
       "name": "Adapter",
       "qty": 1,
       "price": 12
      },
      "message": "$.items[sku=D-400]: element {\"sku\":\"D-400\",\"name\":\"Adapter\",\"qty\":1,\"price\":12} is in target but has no match in source"
     },
     {
      "jsonPath": "$.note",
      "type": "MISSING_IN_SOURCE",
      "source": null,
      "target": "gift wrap",
      "message": "$.note: missing in source, present in target (\"gift wrap\")"
     }
    ]
   },
   {
    "path": "/products/1001",
    "status": "IDENTICAL",
    "summary": "identical"
   },
   {
    "path": "/customers/43/orders",
    "status": "ERROR",
    "summary": "target_service returned HTTP 500"
   },
   {
    "path": "/customers/42/addresses",
    "status": "DIFFERENT",
    "summary": "1 difference",
    "differenceCount": 1,
    "byType": {
     "VALUE_MISMATCH": 1
    },
    "differences": [
     {
      "jsonPath": "$.rows[1].floor",
      "targetPath": "$.rows[0].floor",
      "location": "$.rows[1: name=Work, city=Leeds, floor=1].floor",
      "type": "VALUE_MISMATCH",
      "source": 1,
      "target": 2,
      "message": "$.rows[1: name=Work, city=Leeds, floor=1].floor: source=1, target=2"
     }
    ]
   }
  ]
 },
 "docs": {
  "0": {
   "source": {
    "orderId": 42,
    "total": 100,
    "currency": "EUR",
    "generatedBy": "v1",
    "customer": {
     "name": "Ada Lovelace",
     "email": null,
     "address": {
      "city": "London",
      "zip": "N1"
     }
    },
    "tags": [
     "vip",
     "eu",
     "new"
    ],
    "items": [
     {
      "sku": "A-100",
      "name": "Keyboard",
      "qty": 1,
      "price": 49.9
     },
     {
      "sku": "B-200",
      "name": "Mouse",
      "qty": 2,
      "price": 19.5,
      "discount": 5
     },
     {
      "sku": "C-300",
      "name": "Cable",
      "qty": 3,
      "price": 4
     }
    ]
   },
   "target": {
    "orderId": 42,
    "total": 105,
    "currency": "EUR",
    "generatedBy": "v2",
    "customer": {
     "name": "Ada Lovelace",
     "email": "ada@example.com",
     "address": {
      "city": "Paris",
      "zip": "N1"
     }
    },
    "tags": [
     "vip",
     "eu",
     "beta"
    ],
    "items": [
     {
      "sku": "B-200",
      "name": "Mouse",
      "qty": 3,
      "price": 19.5
     },
     {
      "sku": "A-100",
      "name": "Keyboard",
      "qty": 1,
      "price": 49.9
     },
     {
      "sku": "D-400",
      "name": "Adapter",
      "qty": 1,
      "price": 12
     }
    ],
    "note": "gift wrap"
   }
  },
  "1": {
   "source": {
    "id": 1001,
    "name": "Laptop",
    "specs": {
     "ram": 16,
     "ssd": 512
    }
   },
   "target": {
    "specs": {
     "ssd": 512,
     "ram": 16
    },
    "name": "Laptop",
    "id": 1001
   }
  },
  "2": {
   "source": {
    "orderId": 43,
    "total": 12,
    "items": [
     {
      "sku": "Z-1",
      "qty": 1
     }
    ]
   }
  },
  "3": {
   "source": {
    "rows": [
     {
      "name": "Home",
      "city": "London",
      "floor": 3
     },
     {
      "name": "Work",
      "city": "Leeds",
      "floor": 1
     }
    ]
   },
   "target": {
    "rows": [
     {
      "name": "Work",
      "city": "Leeds",
      "floor": 2
     },
     {
      "name": "Home",
      "city": "London",
      "floor": 3
     }
    ]
   }
  }
 }
};
