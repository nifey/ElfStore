import hashlib
import json
import sys
import time

import module_EdgeClientCLI_put

EDGE_ID = int()
EDGE_IP = str()
EDGE_PORT = int()
EDGE_RELI = float()
FOG_IP = str()
FOG_PORT = int()
CLIENT_ID = str()
COMP_FORMAT = str()

def do_put(path,streamId,start,metadata,fogIp,fogPort,edgeId,clientId,duration,comp,erasureCode):
    splitChoice = str(1)
    setLease = str(1)
    return module_EdgeClientCLI_put.put(path,streamId,start,metadata, fogIp,fogPort,edgeId,clientId,splitChoice,setLease,erasureCode,duration,comp)


if __name__ == '__main__':
    if len(sys.argv) < 8:
        print("usage: python put_test.py edge_config_file stream_id start_mbid put_times file_to_put erasureCode(1/0) sleep_time_between_puts mbid_tag")
        sys.exit(0);
    CONFIG_FILE = sys.argv[1]
    edgeConfig = json.load(open(CONFIG_FILE,'r'))
    EDGE_ID = edgeConfig['edgeId']
    EDGE_IP = edgeConfig['edgeIp']
    EDGE_PORT = edgeConfig['edgePort']
    EDGE_RELI = edgeConfig['reliability']
    FOG_IP = edgeConfig['fogIp']
    FOG_PORT = edgeConfig['fogPort']
    CLIENT_ID = hashlib.md5(str(EDGE_ID).encode('utf-8')).hexdigest()
    COMP_FORMAT = edgeConfig['compFormat']

    print("Session Information")
    print("Edge ID : " + str(EDGE_ID))
    print("Edge IP : " + EDGE_IP)
    print("Edge Port : " + str(EDGE_PORT))
    print("Edge Reliability : " + str(EDGE_RELI))
    print("Fog IP : " + FOG_IP)
    print("Fog Port : " + str(FOG_PORT))
    print("Storage Location : " + "./DataAndLogs/edge"+str(EDGE_ID)+"_data")
    print("Client ID : " + CLIENT_ID)

    STREAM_ID = sys.argv[2]
    START_ID = sys.argv[3]
    PUT_TIMES = sys.argv[4]
    PATH = sys.argv[5]
    erasureCode = str(sys.argv[6])
    SLEEP_TIME = sys.argv[7]
    MBID_TAG = ""
    if sys.argv[8] != -1:
        MBID_TAG = str(sys.argv[8])

    if int(PUT_TIMES) == -1:
        print("Putting blocks till failure")
        result = 1
        i = 0
        while result==1:
            result = do_put(PATH, STREAM_ID, int(str(int(START_ID) + i)+MBID_TAG), None, FOG_IP, FOG_PORT, EDGE_ID, CLIENT_ID, str(0), COMP_FORMAT, erasureCode)
            i = i+1
            time.sleep(int(SLEEP_TIME));
            print(result)
            print("res "+str(result == 1))
        print("Terminated after putting "+ str(i)+" blocks")
    else:
        print("Putting "+str(PUT_TIMES)+" blocks")
        for i in range(int(PUT_TIMES)):
            do_put(PATH, STREAM_ID, int(str(int(START_ID) + i)+MBID_TAG), None, FOG_IP, FOG_PORT, EDGE_ID, CLIENT_ID, str(0), COMP_FORMAT, erasureCode)
            time.sleep(int(SLEEP_TIME));
