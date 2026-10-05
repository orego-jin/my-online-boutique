-- KEYS[1]: Inventory key
local value = redis.call('GET', KEYS[1])

-- Not registered product
if not value then
    return -2
end

local stock = tonumber(value)
if not stock or stock < 0 or stock ~= math.floor(stock) then
    return redis.error_reply('Stock must be a non-negative integer')
end

-- Out of stock 
if stock == 0 then
    return -1
end

-- Process one request per time
-- return the remaining inventory
return redis.call('DECR', KEYS[1])
