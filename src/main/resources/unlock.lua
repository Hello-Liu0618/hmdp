-- 释放锁lua脚本

-- 比较线程标识和锁中的标识是否一致
if ( redis.call('get', KEY[1]) == ARGV[1] ) then
    -- 释放锁 del key
    return redis.call('delete', KEY[1])
end
return 0