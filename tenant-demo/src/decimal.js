function parse(value) {
  const text = String(value ?? "").trim();
  const match = /^([+-]?)(\d+)(?:\.(\d+))?$/.exec(text);
  if (!match) {
    throw new Error(`invalid decimal amount: ${text}`);
  }
  const fraction = match[3] ?? "";
  const sign = match[1] === "-" ? -1n : 1n;
  const digits = `${match[2]}${fraction}`.replace(/^0+(?=\d)/, "");
  return { unscaled: sign * BigInt(digits), scale: fraction.length };
}

function align(left, right) {
  const scale = Math.max(left.scale, right.scale);
  const expand = value => value.unscaled * 10n ** BigInt(scale - value.scale);
  return { left: expand(left), right: expand(right), scale };
}

function format(unscaled, scale) {
  if (unscaled === 0n) return "0";
  const negative = unscaled < 0n;
  let digits = (negative ? -unscaled : unscaled).toString();
  if (scale > 0) {
    digits = digits.padStart(scale + 1, "0");
    const integer = digits.slice(0, -scale);
    const fraction = digits.slice(-scale).replace(/0+$/, "");
    digits = fraction ? `${integer}.${fraction}` : integer;
  }
  return negative ? `-${digits}` : digits;
}

export function normalizeDecimal(value) {
  const parsed = parse(value);
  return format(parsed.unscaled, parsed.scale);
}

export function addDecimal(first, second) {
  const values = align(parse(first), parse(second));
  return format(values.left + values.right, values.scale);
}

export function subtractDecimal(first, second) {
  const values = align(parse(first), parse(second));
  const result = values.left - values.right;
  if (result < 0n) {
    throw new Error("insufficient available balance");
  }
  return format(result, values.scale);
}

export function compareDecimal(first, second) {
  const values = align(parse(first), parse(second));
  return values.left === values.right ? 0 : values.left > values.right ? 1 : -1;
}

export function requirePositiveDecimal(value) {
  const normalized = normalizeDecimal(value);
  if (compareDecimal(normalized, "0") <= 0) {
    throw new Error("amount must be greater than zero");
  }
  return normalized;
}
